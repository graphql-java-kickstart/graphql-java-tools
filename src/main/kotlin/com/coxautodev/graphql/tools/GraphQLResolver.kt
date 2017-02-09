package com.coxautodev.graphql.tools

/**
 * @author Andrew Potter
 */
abstract class GraphQLResolver (private val dataType: Class<*>?) {
    fun graphQLResolverDataType(): Class<*>? = dataType
    fun graphQLResolverDataName(): String = graphQLResolverDataType()?.simpleName ?: this.javaClass.simpleName
}

abstract class GraphQLRootResolver : GraphQLResolver(null)