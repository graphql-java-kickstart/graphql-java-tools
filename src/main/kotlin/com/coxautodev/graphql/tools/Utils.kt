package com.coxautodev.graphql.tools

import graphql.language.FieldDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.Type
import java.lang.reflect.ParameterizedType

/**
 * @author Andrew Potter
 */

internal typealias GraphQLRootResolver = GraphQLResolver<Void>

internal typealias JavaType = java.lang.reflect.Type
internal typealias GraphQLLangType = graphql.language.Type<*>

internal fun Type<*>.unwrap(): Type<*> = when (this) {
    is NonNullType -> this.type.unwrap()
    is ListType -> this.type.unwrap()
    else -> this
}

internal fun ObjectTypeDefinition.getExtendedFieldDefinitions(extensions: List<ObjectTypeExtensionDefinition>): List<FieldDefinition> {
    return this.fieldDefinitions + extensions.filter { it.name == this.name }.flatMap { it.fieldDefinitions }
}

internal fun JavaType.unwrap(): Class<out Any> =
        if (this is ParameterizedType) {
            this.rawType as Class<*>
        } else {
            this as Class<*>
        }
