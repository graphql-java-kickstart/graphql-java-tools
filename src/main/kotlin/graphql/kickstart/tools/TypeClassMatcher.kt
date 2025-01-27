package graphql.kickstart.tools

import graphql.execution.DataFetcherResult
import graphql.kickstart.tools.util.GraphQLLangType
import graphql.kickstart.tools.util.JavaType
import graphql.language.*
import graphql.schema.idl.ScalarInfo
import java.lang.reflect.ParameterizedType
import java.util.*

/**
 * @author Andrew Potter
 */
internal class TypeClassMatcher(private val definitionsByName: Map<String, TypeDefinition<*>>) {

    companion object {
        fun isListType(realType: ParameterizedType, generic: GenericType) = generic.isTypeAssignableFromRawClass(realType, Iterable::class.java)
    }

    private fun error(potentialMatch: PotentialMatch, msg: String) = SchemaClassScannerError("Unable to match type definition (${potentialMatch.graphQLType}) for reference ${potentialMatch.reference} with java type (${potentialMatch.javaType}): $msg")

    fun match(potentialMatch: PotentialMatch): Match {
        return match(potentialMatch, potentialMatch.graphQLType, potentialMatch.javaType, true)
    }

    private fun match(potentialMatch: PotentialMatch, graphQLType: GraphQLLangType, javaType: JavaType, root: Boolean = false): Match {

        var realType = potentialMatch.generic.unwrapGenericType(javaType)

        if (realType is ParameterizedType && potentialMatch.generic.isTypeAssignableFromRawClass(realType, DataFetcherResult::class.java)) {
            if (potentialMatch.location != Location.RETURN_TYPE) {
                throw error(potentialMatch, "${DataFetcherResult::class.java.name} can only be used as a return type")
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
                } else if (realType is Class<*> && realType.isArray) {
                    match(potentialMatch, graphQLType.type, realType.componentType)
                } else {
                    throw error(potentialMatch, "Java class is not a List or generic type information was lost: $realType")
                }
            }

            is TypeName -> {
                val typeDefinition = ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS[graphQLType.name]
                    ?: definitionsByName[graphQLType.name]
                    ?: throw error(potentialMatch, "No ${TypeDefinition::class.java.simpleName} for type name ${graphQLType.name}")
                if (typeDefinition is ScalarTypeDefinition) {
                    ScalarMatch(typeDefinition)
                } else {
                    ValidMatch(typeDefinition, realType, potentialMatch.reference)
                }
            }

            is TypeDefinition<*> -> ValidMatch(graphQLType, realType, potentialMatch.reference)
            else -> throw error(potentialMatch, "Unknown type: ${realType.javaClass.name}")
        }
    }

    private fun isListType(realType: ParameterizedType, potentialMatch: PotentialMatch) = isListType(realType, potentialMatch.generic)

    internal sealed interface Match

    internal data class ScalarMatch(val type: ScalarTypeDefinition) : Match

    internal data class ValidMatch(val type: TypeDefinition<*>, val javaType: JavaType, val reference: SchemaClassScanner.Reference) : Match

    internal enum class Location(val prettyName: String) {
        RETURN_TYPE("return type"),
        PARAMETER_TYPE("parameter"),
    }

    internal data class PotentialMatch(
        val graphQLType: GraphQLLangType,
        val javaType: JavaType,
        val generic: GenericType.RelativeTo,
        val reference: SchemaClassScanner.Reference,
        val location: Location
    ) {
        companion object {
            fun returnValue(graphQLType: GraphQLLangType, javaType: JavaType, generic: GenericType.RelativeTo, reference: SchemaClassScanner.Reference) =
                PotentialMatch(graphQLType, javaType, generic, reference, Location.RETURN_TYPE)

            fun parameterType(graphQLType: GraphQLLangType, javaType: JavaType, generic: GenericType.RelativeTo, reference: SchemaClassScanner.Reference) =
                PotentialMatch(graphQLType, javaType, generic, reference, Location.PARAMETER_TYPE)
        }
    }
}
