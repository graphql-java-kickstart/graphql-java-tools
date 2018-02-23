package com.coxautodev.graphql.tools

import graphql.language.*
import graphql.schema.idl.ScalarInfo
import org.apache.commons.lang3.reflect.TypeUtils
import java.lang.reflect.ParameterizedType

/**
 * @author Andrew Potter
 */
internal class TypeClassMatcher(private val definitionsByName: Map<String, TypeDefinition>) {

    companion object {
        fun isListType(realType: ParameterizedType, generic: GenericType) = generic.isTypeAssignableFromRawClass(realType, Iterable::class.java)
    }

    private fun error(potentialMatch: PotentialMatch, msg: String) = SchemaClassScannerError("Unable to match type definition (${potentialMatch.graphQLType}) with java type (${potentialMatch.javaType}): $msg")

    fun match(potentialMatch: PotentialMatch): Match {
        return if(potentialMatch.batched) {
            match(stripBatchedType(potentialMatch)) // stripBatchedType sets 'batched' to false
        } else {
            match(potentialMatch, potentialMatch.graphQLType, potentialMatch.javaType)
        }
    }

    private fun match(potentialMatch: PotentialMatch, graphQLType: GraphQLLangType, javaType: JavaType): Match {

        val realType = potentialMatch.generic.unwrapGenericType(javaType)

        // Match graphql type to java type.
        return when(graphQLType) {
            is NonNullType -> {
                match(potentialMatch, graphQLType.type, realType)
            }

            is ListType -> {
                if(realType is ParameterizedType && isListType(realType, potentialMatch)) {
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

    private fun isListType(realType: ParameterizedType, potentialMatch: PotentialMatch) = isListType(realType, potentialMatch.generic)

    private fun requireRawClass(type: JavaType): Class<*> {
        if(type !is Class<*>) {
            throw RawClassRequiredForGraphQLMappingException("Type ${TypeUtils.toString(type)} cannot be mapped to a GraphQL type!  Since GraphQL-Java deals with erased types at runtime, only non-parameterized classes can represent a GraphQL type.  This allows for reverse-lookup by java class in interfaces and union types.")
        }

        return type
    }

    private fun stripBatchedType(potentialMatch: PotentialMatch): PotentialMatch {
        val realType = potentialMatch.generic.unwrapGenericType(potentialMatch.javaType)

        if(realType is ParameterizedType && isListType(realType, potentialMatch)) {
            return potentialMatch.copy(javaType = realType.actualTypeArguments.first(), batched = false)
        } else {
            throw error(potentialMatch, "Method was marked as @Batched but ${potentialMatch.location.prettyName} was not a list!")
        }
    }

    internal interface Match
    internal data class ScalarMatch(val type: ScalarTypeDefinition): Match
    internal data class ValidMatch(val type: TypeDefinition, val clazz: Class<*>, val reference: SchemaClassScanner.Reference): Match
    internal enum class Location(val prettyName: String) {
        RETURN_TYPE("return type"),
        PARAMETER_TYPE("parameter"),
    }

    internal data class PotentialMatch(val graphQLType: GraphQLLangType, val javaType: JavaType, val generic: GenericType.RelativeTo, val reference: SchemaClassScanner.Reference, val location: Location, val batched: Boolean) {
        companion object {
            fun returnValue(graphQLType: GraphQLLangType, javaType: JavaType, generic: GenericType.RelativeTo, reference: SchemaClassScanner.Reference, batched: Boolean) =
                PotentialMatch(graphQLType, javaType, generic, reference, Location.RETURN_TYPE, batched)

            fun parameterType(graphQLType: GraphQLLangType, javaType: JavaType, generic: GenericType.RelativeTo, reference: SchemaClassScanner.Reference, batched: Boolean) =
                PotentialMatch(graphQLType, javaType, generic, reference, Location.PARAMETER_TYPE, batched)
        }
    }
    class RawClassRequiredForGraphQLMappingException(msg: String): RuntimeException(msg)
}
