package com.coxautodev.graphql.tools

import graphql.language.FieldDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.Type
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy

/**
 * @author Andrew Potter
 */

internal typealias GraphQLRootResolver = GraphQLResolver<Void>

internal typealias JavaType = java.lang.reflect.Type
internal typealias JavaMethod = java.lang.reflect.Method
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



internal fun DataFetchingEnvironment.coroutineScope(): CoroutineScope {
    val context: Any? = this.getContext()
    return if (context is CoroutineScope) context else CoroutineScope(Dispatchers.Default)
}

internal val Class<*>.declaredNonProxyMethods: List<JavaMethod>
    get() {
        return when {
            Proxy.isProxyClass(this) -> emptyList()
            else -> this.declaredMethods.toList()
        }
    }


/**
 * Simple heuristic to check is a method is a trivial data fetcher.
 *
 * Requirements are:
 * prefixed with get
 * must have zero parameters
 */
internal fun isTrivialDataFetcher(method: Method): Boolean {
    return (method.parameterCount == 0
            && (
            method.name.startsWith("get")
                    || isBooleanGetter(method)))
}

private fun isBooleanGetter(method: Method) = (method.name.startsWith("is")
        && (method.returnType == java.lang.Boolean::class.java)
        || method.returnType == Boolean::class.java)

