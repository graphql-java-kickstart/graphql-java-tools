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
        private val dictionary: BiMap<JavaType, TypeDefinition<*>>
) : TypeResolver {
    private fun <T> getTypeDefinition(clazz: Class<T>): TypeDefinition<*>? {
        return dictionary[clazz]
            ?: (if (clazz.superclass == null) null else getTypeDefinition(clazz.superclass))
            ?: clazz.interfaces.mapNotNull { getTypeDefinition(it) }.firstOrNull()
    }

    override fun getType(env: TypeResolutionEnvironment): GraphQLObjectType? {
        val clazz = env.getObject<Any>().javaClass
        val name = getTypeDefinition(clazz)?.name ?: clazz.simpleName
        return env.schema.getObjectType(name) ?: throw TypeResolverError(getError(name))
    }

    abstract fun getError(name: String): String
}

internal class InterfaceTypeResolver(
    dictionary: BiMap<JavaType, TypeDefinition<*>>,
    private val thisInterface: GraphQLInterfaceType
) : DictionaryTypeResolver(
        dictionary
) {
    override fun getError(name: String) = "Expected object type with name '$name' to implement interface '${thisInterface.name}', but it doesn't!"
}

internal class UnionTypeResolver(
    dictionary: BiMap<JavaType, TypeDefinition<*>>,
    private val thisUnion: GraphQLUnionType
) : DictionaryTypeResolver(
        dictionary
) {
    override fun getError(name: String) = "Expected object type with name '$name' to exist for union '${thisUnion.name}', but it doesn't!"
}

internal class TypeResolverError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
