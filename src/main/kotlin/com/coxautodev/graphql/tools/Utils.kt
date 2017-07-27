package com.coxautodev.graphql.tools

import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.Type

/**
 * @author Andrew Potter
 */

internal typealias GraphQLRootResolver = GraphQLResolver<Void>
internal typealias JavaType = java.lang.reflect.Type
internal typealias GraphQLLangType = graphql.language.Type

internal fun Type.unwrap(): Type = when(this) {
    is NonNullType -> this.type.unwrap()
    is ListType -> this.type.unwrap()
    else -> this
}

internal fun <E, R> List<E>.findTransformedNotNull(transform: (E) -> R?): R? {
    for(element in this) {
        val result = transform(element)
        if(result != null) {
            return result
        }
    }

    return null
}
