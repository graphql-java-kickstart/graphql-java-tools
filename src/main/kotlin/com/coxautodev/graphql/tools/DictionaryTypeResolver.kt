package com.coxautodev.graphql.tools

import com.google.common.collect.BiMap
import graphql.TypeResolutionEnvironment
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLUnionType
import graphql.schema.TypeResolver

/**
 * @author Andrew Potter
 */
abstract class DictionaryTypeResolver(private val dictionary: BiMap<Class<*>, String>, private val types: Map<String, GraphQLObjectType>) : TypeResolver {

    override fun getType(env: TypeResolutionEnvironment): GraphQLObjectType? {
        val clazz = env.`object`.javaClass
        val name = dictionary[clazz] ?: clazz.simpleName

        return types[name] ?: throw TypeResolverError(getError(name))
    }

    abstract fun getError(name: String): String
}

class InterfaceTypeResolver(dictionary: BiMap<Class<*>, String>, private val thisInterface: GraphQLInterfaceType, types: List<GraphQLObjectType>) : DictionaryTypeResolver(dictionary, types.filter { it.interfaces.any { it.name == thisInterface.name } }.associateBy { it.name }) {
    override fun getError(name: String) = "Expected object type with name '$name' to implement interface '${thisInterface.name}', but it doesn't!"
}

class UnionTypeResolver(dictionary: BiMap<Class<*>, String>, private val thisUnion: GraphQLUnionType, types: List<GraphQLObjectType>) : DictionaryTypeResolver(dictionary, types.filter { type -> thisUnion.types.any { it.name == type.name } }.associateBy { it.name }) {
    override fun getError(name: String) = "Expected object type with name '$name' to exist for union '${thisUnion.name}', but it doesn't!"
}

class TypeResolverError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
