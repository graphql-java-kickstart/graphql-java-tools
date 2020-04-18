package graphql.kickstart.tools

import graphql.TypeResolutionEnvironment
import graphql.kickstart.tools.util.BiMap
import graphql.kickstart.tools.util.JavaType
import graphql.language.TypeDefinition
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLUnionType
import graphql.schema.TypeResolver

/**
 * @author Andrew Potter
 */
internal abstract class DictionaryTypeResolver(
    private val dictionary: BiMap<JavaType, TypeDefinition<*>>,
    private val types: Map<String, GraphQLObjectType>
) : TypeResolver {
    private fun <T> getTypeName(clazz: Class<T>): String? {
        val name = dictionary[clazz]?.name

        if (name == null && clazz.superclass != null) {
            return getTypeName(clazz.superclass)
        }

        return name
    }

    override fun getType(env: TypeResolutionEnvironment): GraphQLObjectType? {
        val clazz = env.getObject<Any>().javaClass
        val name = clazz.interfaces.fold(getTypeName(clazz), { name, interfaceClazz ->
            name ?: getTypeName(interfaceClazz)
        }) ?: clazz.simpleName

        return types[name] ?: throw TypeResolverError(getError(name))
    }

    abstract fun getError(name: String): String
}

internal class InterfaceTypeResolver(
    dictionary: BiMap<JavaType, TypeDefinition<*>>,
    private val thisInterface: GraphQLInterfaceType,
    types: List<GraphQLObjectType>
) : DictionaryTypeResolver(
    dictionary,
    types.filter { type -> type.interfaces.any { it.name == thisInterface.name } }.associateBy { it.name }
) {
    override fun getError(name: String) = "Expected object type with name '$name' to implement interface '${thisInterface.name}', but it doesn't!"
}

internal class UnionTypeResolver(
    dictionary: BiMap<JavaType, TypeDefinition<*>>,
    private val thisUnion: GraphQLUnionType,
    types: List<GraphQLObjectType>
) : DictionaryTypeResolver(
    dictionary,
    types.filter { type -> thisUnion.types.any { it.name == type.name } }.associateBy { it.name }
) {
    override fun getError(name: String) = "Expected object type with name '$name' to exist for union '${thisUnion.name}', but it doesn't!"
}

internal class TypeResolverError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
