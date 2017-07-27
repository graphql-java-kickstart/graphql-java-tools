package com.coxautodev.graphql.tools

import graphql.language.FieldDefinition
import java.lang.reflect.Method

/**
 * @author Andrew Potter
 */
internal abstract class FieldResolver(val field: FieldDefinition, val resolverInfo: ResolverInfo, val search: FieldResolverScanner.Search) {

}

internal class MethodFieldResolver(field: FieldDefinition, resolverInfo: ResolverInfo, search: FieldResolverScanner.Search, private val method: Method): FieldResolver(field, resolverInfo, search) {

    val genericType = GenericType(search.type).relativeTo(method.declaringClass)
    val dataFetchingEnvironment = method.parameterCount == (field.inputValueDefinitions.size + getIndexOffset() + 1)
    val sourceArgument = search.isNonRootResolver()

    private fun getIndexOffset() = if(sourceArgument) 1 else 0
    fun getJavaMethodParameterIndex(index: Int) = index + getIndexOffset()

    fun getJavaMethodParameterType(index: Int): JavaType? {
        val methodIndex = getJavaMethodParameterIndex(index)
        val parameters = method.parameterTypes
        if(parameters.size > methodIndex) {
            return method.genericParameterTypes[getJavaMethodParameterIndex(index)]
        } else {
            return null
        }
    }
}

internal class PropertyFieldResolver(field: FieldDefinition, resolverInfo: ResolverInfo, search: FieldResolverScanner.Search): FieldResolver(field, resolverInfo, search) {

}
