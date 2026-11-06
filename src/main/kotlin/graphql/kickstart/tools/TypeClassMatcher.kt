package graphql.kickstart.tools

import graphql.kickstart.tools.resolver.TypeShape
import graphql.kickstart.tools.util.JavaType
import graphql.language.*

/**
 * @author Andrew Potter
 */
internal class TypeClassMatcher(private val definitions: MergedDefinitions) {

    fun match(shape: TypeShape): Match {
        return when (val graphQLType = shape.leafGraphQLType) {
            is TypeName -> {
                val typeDefinition = definitions.resolve(graphQLType.name)
                    ?: throw shape.error("No ${TypeDefinition::class.java.simpleName} for type name ${graphQLType.name}")
                if (typeDefinition is MergedType.Scalar) {
                    ScalarMatch(typeDefinition)
                } else {
                    ValidMatch(typeDefinition, shape.leafJavaType, shape.reference)
                }
            }

            else -> throw shape.error("Unknown type: ${shape.leafJavaType.javaClass.name}")
        }
    }

    internal sealed interface Match

    internal data class ScalarMatch(val type: MergedType.Scalar) : Match

    internal data class ValidMatch(val type: MergedType<*>, val javaType: JavaType, val reference: ScanDictionary.Reference) : Match
}
