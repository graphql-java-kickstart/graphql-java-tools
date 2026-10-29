package graphql.kickstart.tools

import graphql.execution.DataFetcherResult
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper
import graphql.kickstart.tools.util.GraphQLLangType
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.isSubtypeOf
import graphql.language.*
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.TypeUtil
import java.lang.reflect.ParameterizedType
import java.util.*

/**
 * @author Andrew Potter
 */
internal class TypeClassMatcher(private val definitionsByName: Map<String, TypeDefinition<*>>) {

    fun match(potentialMatch: PotentialMatch): Match {
        val unwrapping = potentialMatch.unwrapping
        return when (val graphQLType = unwrapping.graphQLType) {
            is TypeName -> {
                val typeDefinition = ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS[graphQLType.name]
                    ?: definitionsByName[graphQLType.name]
                    ?: throw potentialMatch.error("No ${TypeDefinition::class.java.simpleName} for type name ${graphQLType.name}")
                if (typeDefinition is ScalarTypeDefinition) {
                    ScalarMatch(typeDefinition)
                } else {
                    ValidMatch(typeDefinition, unwrapping.javaType, potentialMatch.reference)
                }
            }

            is TypeDefinition<*> -> ValidMatch(graphQLType, unwrapping.javaType, potentialMatch.reference)
            else -> throw potentialMatch.error("Unknown type: ${unwrapping.javaType.javaClass.name}")
        }
    }

    internal sealed interface Match

    internal data class ScalarMatch(val type: ScalarTypeDefinition) : Match

    internal data class ValidMatch(val type: TypeDefinition<*>, val javaType: JavaType, val reference: ScanDictionary.Reference) : Match

    internal enum class Location(val prettyName: String) {
        RETURN_TYPE("return type"),
        PARAMETER_TYPE("parameter"),
    }

    internal data class PotentialMatch(
        val graphQLType: GraphQLLangType,
        val javaType: JavaType,
        val typeResolver: GenericTypeResolver,
        val reference: ScanDictionary.Reference,
        val location: Location
    ) {
        val unwrapping: Unwrapping by lazy { unwrap(graphQLType, javaType, root = true, mutableListOf()) }

        fun error(msg: String) = SchemaClassScannerError("Unable to match type definition (${TypeUtil.simplePrint(graphQLType)}) for reference $reference with java type ($javaType): $msg")

        private fun unwrap(graphQLType: GraphQLLangType, javaType: JavaType, root: Boolean, unwrapped: MutableList<Unwrapped>): Unwrapping {
            val onUnwrap = { wrapper: GenericWrapper -> unwrapped += Unwrapped.Wrapper(wrapper) }
            var realType = typeResolver.resolve(javaType, onUnwrap)
            var data = false
            var optional = false

            while (realType is ParameterizedType) {
                when {
                    realType.isSubtypeOf(DataFetcherResult::class.java) -> {
                        if (location != Location.RETURN_TYPE) {
                            throw error("${DataFetcherResult::class.java.name} can only be used as a return type")
                        }
                        if (data) {
                            throw error("${DataFetcherResult::class.java.name} cannot be nested within itself")
                        }
                        data = true
                        unwrapped += Unwrapped.Data
                    }

                    realType.isSubtypeOf(Optional::class.java) -> {
                        if (location == Location.RETURN_TYPE && !root) {
                            throw error("${Optional::class.java.name} can only be used at the top level of a return type")
                        }
                        if (optional) {
                            throw error("${Optional::class.java.name} cannot be nested within itself")
                        }
                        optional = true
                        unwrapped += Unwrapped.Present
                    }

                    else -> break
                }
                realType = typeResolver.resolve(realType.actualTypeArguments.first(), onUnwrap)
            }

            // Match graphql type to java type.
            return when (graphQLType) {
                is NonNullType -> {
                    if (optional) {
                        throw error("graphql type is marked as nonnull but ${Optional::class.java.name} was used")
                    }
                    unwrap(graphQLType.type, realType, root = false, unwrapped)
                }

                is ListType -> when {
                    realType is ParameterizedType && realType.isSubtypeOf(Iterable::class.java) -> {
                        unwrapped += Unwrapped.Elements
                        unwrap(graphQLType.type, realType.actualTypeArguments.first(), root = false, unwrapped)
                    }
                    realType is Class<*> && realType.isArray ->
                        unwrap(graphQLType.type, realType.componentType, root = false, unwrapped)
                    // e.g. a property map value: the element type is unknown, so match it as Object too
                    realType == Any::class.java ->
                        unwrap(graphQLType.type, realType, root = false, unwrapped)
                    else ->
                        throw error("Java class is not a List or generic type information was lost: $realType")
                }

                else -> Unwrapping(unwrapped, graphQLType, realType)
            }
        }

        companion object {
            fun returnValue(graphQLType: GraphQLLangType, javaType: JavaType, typeResolver: GenericTypeResolver, reference: ScanDictionary.Reference) =
                PotentialMatch(graphQLType, javaType, typeResolver, reference, Location.RETURN_TYPE)

            fun parameterType(graphQLType: GraphQLLangType, javaType: JavaType, typeResolver: GenericTypeResolver, reference: ScanDictionary.Reference) =
                PotentialMatch(graphQLType, javaType, typeResolver, reference, Location.PARAMETER_TYPE)
        }
    }

    internal class Unwrapping(val unwrapped: List<Unwrapped>, val graphQLType: GraphQLLangType, val javaType: JavaType)

    internal sealed interface Unwrapped {
        class Wrapper(val wrapper: GenericWrapper) : Unwrapped
        object Data : Unwrapped
        object Present : Unwrapped
        object Elements : Unwrapped
    }
}
