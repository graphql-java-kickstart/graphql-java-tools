package com.coxautodev.graphql.tools

import graphql.execution.DataFetcherResult
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ScalarTypeDefinition
import graphql.language.TypeDefinition
import graphql.language.TypeName
import graphql.schema.idl.ScalarInfo
import org.apache.commons.lang3.reflect.TypeUtils
import sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl
import java.lang.reflect.ParameterizedType
import java.util.*

/**
 * @author Andrew Potter
 */
internal class TypeClassMatcher(private val definitionsByName: Map<String, TypeDefinition<*>>) {

    companion object {
        fun isListType(realType: ParameterizedType, generic: GenericType) = generic.isTypeAssignableFromRawClass(realType, Iterable::class.java)
    }

    private fun error(potentialMatch: PotentialMatch, msg: String) = SchemaClassScannerError("Unable to match type definition (${potentialMatch.graphQLType}) with java type (${potentialMatch.javaType}): $msg")

    fun match(potentialMatch: PotentialMatch): Match {
        return if (potentialMatch.batched) {
            match(stripBatchedType(potentialMatch)) // stripBatchedType sets 'batched' to false
        } else {
            match(potentialMatch, potentialMatch.graphQLType, potentialMatch.javaType, true)
        }
    }

    private fun match(potentialMatch: PotentialMatch, graphQLType: GraphQLLangType, javaType: JavaType, root: Boolean = false): Match {

        var realType = potentialMatch.generic.unwrapGenericType(javaType)

        if (realType is ParameterizedType && potentialMatch.generic.isTypeAssignableFromRawClass(realType, DataFetcherResult::class.java)) {
            if (potentialMatch.location != Location.RETURN_TYPE) {
                throw error(potentialMatch, "${DataFetcherResult::class.java.name} can only be used as a return type")
            }

            if (!root) {
                throw error(potentialMatch, "${DataFetcherResult::class.java.name} can only be used at the top level of a return type")
            }

            realType = potentialMatch.generic.unwrapGenericType(realType.actualTypeArguments.first())

            if (realType is ParameterizedType && potentialMatch.generic.isTypeAssignableFromRawClass(realType, DataFetcherResult::class.java)) {
                throw error(potentialMatch, "${DataFetcherResult::class.java.name} cannot be nested within itself")
            }
        }

        var optional = false

        // Handle jdk8 Optionals
        if (realType is ParameterizedType && potentialMatch.generic.isTypeAssignableFromRawClass(realType, Optional::class.java)) {
            optional = true

            if (potentialMatch.location == Location.RETURN_TYPE && !root) {
                throw error(potentialMatch, "${Optional::class.java.name} can only be used at the top level of a return type")
            }

            realType = potentialMatch.generic.unwrapGenericType(realType.actualTypeArguments.first())

            if (realType is ParameterizedType && potentialMatch.generic.isTypeAssignableFromRawClass(realType, Optional::class.java)) {
                throw error(potentialMatch, "${Optional::class.java.name} cannot be nested within itself")
            }
        }

        // Match graphql type to java type.
        return when (graphQLType) {
            is NonNullType -> {
                if (optional) {
                    throw error(potentialMatch, "graphql type is marked as nonnull but ${Optional::class.java.name} was used")
                }
                match(potentialMatch, graphQLType.type, realType)
            }

            is ListType -> {
                if (realType is ParameterizedType && isListType(realType, potentialMatch)) {
                    match(potentialMatch, graphQLType.type, realType.actualTypeArguments.first())
                } else {
                    throw error(potentialMatch, "Java class is not a List or generic type information was lost: $realType")
                }
            }

            is TypeName -> {
                val typeDefinition = ScalarInfo.STANDARD_SCALAR_DEFINITIONS[graphQLType.name]
                        ?: definitionsByName[graphQLType.name]
                        ?: throw error(potentialMatch, "No ${TypeDefinition::class.java.simpleName} for type name ${graphQLType.name}")
                if (typeDefinition is ScalarTypeDefinition) {
                    ScalarMatch(typeDefinition)
                } else {
                    ValidMatch(typeDefinition, requireRawClass(realType), potentialMatch.reference)
                }
            }

            is TypeDefinition<*> -> ValidMatch(graphQLType, requireRawClass(realType), potentialMatch.reference)
            else -> throw error(potentialMatch, "Unknown type: ${realType.javaClass.name}")
        }
    }

    private fun isListType(realType: ParameterizedType, potentialMatch: PotentialMatch) = isListType(realType, potentialMatch.generic)

    private fun requireRawClass(type: JavaType): Class<out Any> {
//        if (type is ParameterizedTypeImpl) {
//            return type.rawType.javaClass
//        }
//        if (type !is Class<*>) {
//            throw RawClassRequiredForGraphQLMappingException("Type ${TypeUtils.toString(type)} cannot be mapped to a GraphQL type!  Since GraphQL-Java deals with erased types at runtime, only non-parameterized classes can represent a GraphQL type.  This allows for reverse-lookup by java class in interfaces and union types.")
//        }

        return type.javaClass
    }

    private fun stripBatchedType(potentialMatch: PotentialMatch): PotentialMatch {
        return if (potentialMatch.location == Location.PARAMETER_TYPE) {
            potentialMatch.copy(javaType = potentialMatch.javaType, batched = false)
        } else {
            val realType = potentialMatch.generic.unwrapGenericType(potentialMatch.javaType)
            if (realType is ParameterizedType && isListType(realType, potentialMatch)) {
                potentialMatch.copy(javaType = realType.actualTypeArguments.first(), batched = false)
            } else {
                throw error(potentialMatch, "Method was marked as @Batched but ${potentialMatch.location.prettyName} was not a list!")
            }
        }
    }

    internal interface Match
    internal data class ScalarMatch(val type: ScalarTypeDefinition) : Match
    internal data class ValidMatch(val type: TypeDefinition<*>, val clazz: Class<out Any>, val reference: SchemaClassScanner.Reference) : Match
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

    class RawClassRequiredForGraphQLMappingException(msg: String) : RuntimeException(msg)
}
