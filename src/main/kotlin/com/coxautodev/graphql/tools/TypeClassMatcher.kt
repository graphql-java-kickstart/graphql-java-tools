package com.coxautodev.graphql.tools

import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ScalarTypeDefinition
import graphql.language.TypeDefinition
import graphql.language.TypeName
import graphql.schema.idl.ScalarInfo
import org.apache.commons.lang3.reflect.TypeUtils
import java.lang.reflect.ParameterizedType
import java.util.Optional

/**
 * @author Andrew Potter
 */
internal class TypeClassMatcher(private val definitionsByName: Map<String, TypeDefinition>) {

    private fun error(potentialMatch: PotentialMatch, msg: String) = SchemaClassScannerError("Unable to match type definition (${potentialMatch.graphQLType}) with java type (${potentialMatch.javaType}): $msg")

    fun match(potentialMatch: PotentialMatch) = match(potentialMatch, potentialMatch.graphQLType, potentialMatch.javaType, true)

    private fun match(potentialMatch: PotentialMatch, graphQLType: GraphQLLangType, javaType: JavaType, root: Boolean = false): Match {

        var realType = potentialMatch.generic.unwrapGenericType(javaType)
        var optional = false

        // Handle jdk8 Optionals
        if(realType is ParameterizedType && potentialMatch.generic.isTypeAssignableFromRawClass(realType, Optional::class.java)) {
            optional = true

            if(potentialMatch.location == Location.RETURN_TYPE && !root) {
                throw error(potentialMatch, "${Optional::class.java.name} can only be used at the top level of a return type")
            }

            realType = potentialMatch.generic.unwrapGenericType(realType.actualTypeArguments.first())

            if(realType is ParameterizedType && potentialMatch.generic.isTypeAssignableFromRawClass(realType, Optional::class.java)) {
                throw error(potentialMatch, "${Optional::class.java.name} cannot be nested within itself")
            }
        }

        // Match graphql type to java type.
        return when(graphQLType) {
            is NonNullType -> {
                if(optional) {
                    throw error(potentialMatch, "graphql type is marked as nonnull but ${Optional::class.java.name} was used")
                }
                match(potentialMatch, graphQLType.type, realType)
            }

            is ListType -> {
                if(realType is ParameterizedType && potentialMatch.generic.isTypeAssignableFromRawClass(realType, Iterable::class.java)) {
                    match(potentialMatch, graphQLType.type, realType.actualTypeArguments.first())
                } else {
                    throw error(potentialMatch, "Java class is not a List or generic type information was lost: $realType")
                }
            }

            is TypeName -> {
                val typeDefinition = ScalarInfo.STANDARD_SCALAR_DEFINITIONS[graphQLType.name] ?: definitionsByName[graphQLType.name] ?: throw error(potentialMatch, "No ${TypeDefinition::class.java.simpleName} for type name ${graphQLType.name}")
                if(typeDefinition is ScalarTypeDefinition) {
                    ScalarMatch(typeDefinition)
                } else {
                    ValidMatch(typeDefinition, requireRawClass(realType), potentialMatch.reference)
                }
            }

            is TypeDefinition -> ValidMatch(graphQLType, requireRawClass(realType), potentialMatch.reference)
            else -> throw error(potentialMatch, "Unknown type: ${realType.javaClass.name}")
        }
    }

    private fun requireRawClass(type: JavaType): Class<*> {
        if(type !is Class<*>) {
            throw RawClassRequiredForGraphQLMappingException("Type ${TypeUtils.toString(type)} cannot be mapped to a GraphQL type!  Since GraphQL-Java deals with erased types at runtime, only non-parameterized classes can represent a GraphQL type.  This allows for reverse-lookup by java class in interfaces and union types.")
        }

        return type
    }

    internal interface Match
    internal data class ScalarMatch(val type: ScalarTypeDefinition): Match
    internal data class ValidMatch(val type: TypeDefinition, val clazz: Class<*>, val reference: SchemaClassScanner.Reference): Match

    internal enum class Location {
        RETURN_TYPE,
        PARAMETER_TYPE,
    }

    internal data class PotentialMatch private constructor(val graphQLType: GraphQLLangType, val javaType: JavaType, val generic: GenericType.RelativeTo, val reference: SchemaClassScanner.Reference, val location: Location) {
        companion object {
            fun returnValue(graphQLType: GraphQLLangType, javaType: JavaType, generic: GenericType.RelativeTo, reference: SchemaClassScanner.Reference): PotentialMatch {
                return PotentialMatch(graphQLType, javaType, generic, reference, Location.RETURN_TYPE)
            }
            fun parameterType(graphQLType: GraphQLLangType, javaType: JavaType, generic: GenericType.RelativeTo, reference: SchemaClassScanner.Reference): PotentialMatch {
                return PotentialMatch(graphQLType, javaType, generic, reference, Location.PARAMETER_TYPE)
            }
        }
    }
    class RawClassRequiredForGraphQLMappingException(msg: String): RuntimeException(msg)
}
