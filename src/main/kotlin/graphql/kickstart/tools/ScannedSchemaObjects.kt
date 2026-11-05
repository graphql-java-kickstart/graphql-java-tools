package graphql.kickstart.tools

import graphql.kickstart.tools.resolver.FieldResolver
import graphql.kickstart.tools.util.JavaType
import graphql.language.FieldDefinition
import graphql.schema.GraphQLScalarType

/**
 * @author Andrew Potter
 */
internal data class ScannedSchemaObjects(
    val dictionary: TypeClassDictionary,
    val definitions: MergedDefinitions,
    val types: Set<MergedType<*>>,
    val customScalars: CustomScalarMap,
    val fieldResolversByType: Map<MergedType.Object, MutableMap<FieldDefinition, FieldResolver>>,
    val unusedTypes: Set<MergedType<*>>,
    val directiveOnlyEnums: Set<MergedType.Enum>
)

internal class TypeClassDictionary(val classByType: Map<MergedType<*>, JavaType>) {
    val typeByClass: Map<JavaType, MergedType<*>>

    init {
        requireOnlyInputTypesShareAClass(classByType) { javaType, types ->
            "Class '${javaType.typeName}' is used for types ${types.joinToString { "'${it.name}'" }}, but only input object types can share a class!"
        }
        typeByClass = classByType.entries.associate { it.value to it.key }
    }

    companion object {
        fun <J> requireOnlyInputTypesShareAClass(classByType: Map<MergedType<*>, J>, message: (J, List<MergedType<*>>) -> String) {
            classByType.entries
                .filter { it.key !is MergedType.Input }
                .groupBy({ it.value }, { it.key })
                .entries.firstOrNull { it.value.size > 1 }
                ?.let { (javaType, types) -> throw SchemaClassScannerError(message(javaType, types)) }
        }
    }
}

internal typealias CustomScalarMap = Map<String, GraphQLScalarType>
