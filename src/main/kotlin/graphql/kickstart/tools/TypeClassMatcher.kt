package graphql.kickstart.tools

import graphql.kickstart.tools.resolver.TypeShape
import graphql.kickstart.tools.util.JavaType
import graphql.language.*
import graphql.schema.idl.ScalarInfo

/**
 * @author Andrew Potter
 */
internal class TypeClassMatcher(private val definitionsByName: Map<String, TypeDefinition<*>>) {

    fun match(shape: TypeShape): Match {
        return when (val graphQLType = shape.leafGraphQLType) {
            is TypeName -> {
                val typeDefinition = ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS[graphQLType.name]
                    ?: definitionsByName[graphQLType.name]
                    ?: throw shape.error("No ${TypeDefinition::class.java.simpleName} for type name ${graphQLType.name}")
                if (typeDefinition is ScalarTypeDefinition) {
                    ScalarMatch(typeDefinition)
                } else {
                    ValidMatch(typeDefinition, shape.leafJavaType, shape.reference)
                }
            }

            is TypeDefinition<*> -> ValidMatch(graphQLType, shape.leafJavaType, shape.reference)
            else -> throw shape.error("Unknown type: ${shape.leafJavaType.javaClass.name}")
        }
    }

    internal sealed interface Match

    internal data class ScalarMatch(val type: ScalarTypeDefinition) : Match

    internal data class ValidMatch(val type: TypeDefinition<*>, val javaType: JavaType, val reference: ScanDictionary.Reference) : Match
}
