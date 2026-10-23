package graphql.kickstart.tools

import graphql.kickstart.tools.resolver.FieldResolver
import graphql.kickstart.tools.util.JavaType
import graphql.language.EnumTypeDefinition
import graphql.language.FieldDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.SDLNamedDefinition
import graphql.language.TypeDefinition
import graphql.schema.GraphQLScalarType

/**
 * @author Andrew Potter
 */
internal data class ScannedSchemaObjects(
    val dictionary: TypeClassDictionary,
    val definitions: Set<SDLNamedDefinition<*>>,
    val typeExtensions: TypeExtensions,
    val customScalars: CustomScalarMap,
    val rootInfo: RootTypeInfo,
    val fieldResolversByType: Map<ObjectTypeDefinition, MutableMap<FieldDefinition, FieldResolver>>,
    val unusedDefinitions: Set<TypeDefinition<*>>,
    val directiveOnlyEnums: Set<EnumTypeDefinition>
)

internal class TypeClassDictionary(val classByType: Map<TypeDefinition<*>, JavaType>) {
    val typeByClass: Map<JavaType, TypeDefinition<*>> = classByType.entries
        .groupBy({ it.value }, { it.key })
        .mapValues { (javaType, types) ->
            types.singleOrNull() ?: throw SchemaClassScannerError(
                "Class '${javaType.typeName}' is used for types ${types.joinToString { "'${it.name}'" }}, but only input object types can share a class!"
            )
        }
}

internal typealias CustomScalarMap = Map<String, GraphQLScalarType>
internal typealias TypeExtensions = Map<String, List<TypeDefinition<*>>>
