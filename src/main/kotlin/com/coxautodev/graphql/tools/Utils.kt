package com.coxautodev.graphql.tools

import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.Type
import org.apache.commons.lang3.reflect.TypeUtils

/**
 * @author Andrew Potter
 */

internal typealias JavaType = java.lang.reflect.Type
internal typealias GraphQLLangType = graphql.language.Type

internal fun Type.unwrap(): Type = when(this) {
    is NonNullType -> this.type.unwrap()
    is ListType -> this.type.unwrap()
    else -> this
}

class Utils {
    companion object {
    }
}
