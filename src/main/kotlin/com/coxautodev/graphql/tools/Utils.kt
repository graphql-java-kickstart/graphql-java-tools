package com.coxautodev.graphql.tools

import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.Type

/**
 * @author Andrew Potter
 */

fun Type.unwrap(): Type = when(this) {
    is NonNullType -> this.type.unwrap()
    is ListType -> this.type.unwrap()
    else -> this
}

typealias JavaType = java.lang.reflect.Type
typealias GraphQLLangType = graphql.language.Type
