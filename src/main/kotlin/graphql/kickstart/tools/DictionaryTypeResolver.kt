package graphql.kickstart.tools

import graphql.TypeResolutionEnvironment
import graphql.kickstart.tools.util.BiMap
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.unwrap
import graphql.language.TypeDefinition
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLUnionType
import graphql.schema.TypeResolver
import java.lang.reflect.ParameterizedType

/**
 * @author Andrew Potter
 */
internal abstract class DictionaryTypeResolver(
        private val dictionary: BiMap<JavaType, TypeDefinition<*>>
) : TypeResolver {
    // Raw class of each parameterized entry, e.g. Box for Box<String>, unless several entries share it
    private val rawClassDictionary by lazy {
        dictionary.entries
            .filter { it.key is ParameterizedType }
            .groupBy({ it.key.unwrap() }, { it.value })
            .mapNotNull { (clazz, definitions) -> definitions.singleOrNull()?.let { clazz to it } }
            .toMap()
    }

    private fun <T> getTypeDefinition(clazz: Class<T>): TypeDefinition<*>? {
        return dictionary[clazz]
            ?: (if (clazz.superclass == null) null else getTypeDefinition(clazz.superclass))
            ?: clazz.interfaces.mapNotNull { getTypeDefinition(it) }.firstOrNull()
    }

    override fun getType(env: TypeResolutionEnvironment): GraphQLObjectType? {
        val clazz = env.getObject<Any>()!!.javaClass
        val name = getTypeDefinition(clazz)?.name ?: clazz.simpleName
        return env.schema.getType(name) as? GraphQLObjectType
            ?: rawClassDictionary[clazz]?.let { env.schema.getType(it.name) as? GraphQLObjectType }
            // throws graphql-java's AssertException when the name is a type other than an object type
            ?: env.schema.getObjectType(name)
            ?: throw TypeResolverError(getError(name))
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
