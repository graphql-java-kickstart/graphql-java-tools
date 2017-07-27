package com.coxautodev.graphql.tools

import graphql.language.FieldDefinition
import graphql.schema.DataFetcher
import java.lang.reflect.Method

/**
 * @author Andrew Potter
 */
internal abstract class FieldResolver(val field: FieldDefinition, val search: FieldResolverScanner.Search) {
    val resolverInfo: ResolverInfo = search.resolverInfo

    abstract fun scanForMatches(): List<TypeClassMatcher.PotentialMatch>
    abstract fun createDataFetcher(): DataFetcher<*>
}

internal class MethodFieldResolver(field: FieldDefinition, search: FieldResolverScanner.Search, val method: Method): FieldResolver(field, search) {

    override fun createDataFetcher(): DataFetcher<*> {
        return MethodFieldResolverDataFetcher.create(this)
    }

    override fun scanForMatches(): List<TypeClassMatcher.PotentialMatch> {
        val returnValueMatch = TypeClassMatcher.PotentialMatch(field.type, method.genericReturnType, genericType.relativeTo(method.declaringClass), SchemaClassScanner.ReturnValueReference(method), TypeClassMatcher.Location.RETURN_TYPE)

        return field.inputValueDefinitions.mapIndexed { i, inputDefinition ->
            TypeClassMatcher.PotentialMatch(inputDefinition.type, getJavaMethodParameterType(i)!!, genericType.relativeTo(method.declaringClass), SchemaClassScanner.MethodParameterReference(method, i))
        } + listOf(returnValueMatch)
    }

    val genericType = GenericType(search.type).relativeTo(method.declaringClass)
    val dataFetchingEnvironment = method.parameterCount == (field.inputValueDefinitions.size + getIndexOffset() + 1)

    private fun getIndexOffset() = if(resolverInfo is NormalResolverInfo) 1 else 0
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

internal class PropertyFieldResolver(field: FieldDefinition, search: FieldResolverScanner.Search): FieldResolver(field, search) {
    override fun createDataFetcher(): DataFetcher<*> {
        TODO("not implemented")
    }

    override fun scanForMatches(): List<TypeClassMatcher.PotentialMatch> {
        TODO("not implemented")
    }
}
