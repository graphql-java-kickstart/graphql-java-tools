package com.coxautodev.graphql.tools

import com.fasterxml.classmate.TypeResolver
import graphql.language.FieldDefinition
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment

/**
 * @author Nick Weedon
 *
 * The PropertyMapResolver implements the Map (i.e. property map) specific portion of the logic within the GraphQL PropertyDataFetcher class.
 */
internal class PropertyMapResolver(field: FieldDefinition, search: FieldResolverScanner.Search, options: SchemaParserOptions, relativeTo: JavaType): FieldResolver(field, search, options, relativeTo) {

    var mapGenericValue : JavaType = getMapGenericType(relativeTo)

    fun getMapGenericType(mapClass : JavaType) : JavaType {
        val resolvedType = TypeResolver().resolve(mapClass)

        return resolvedType.typeParametersFor(Map::class.java)[1]
    }

    override fun createDataFetcher(): DataFetcher<*> {
        return PropertyMapResolverDataFetcher(getSourceResolver(), field.name)
    }

    override fun scanForMatches(): List<TypeClassMatcher.PotentialMatch> {
        return listOf(TypeClassMatcher.PotentialMatch.returnValue(field.type, mapGenericValue, genericType, SchemaClassScanner.FieldTypeReference(field.name), false))
    }

    override fun toString() = "PropertyMapResolverDataFetcher{key=${field.name}}"
}

class PropertyMapResolverDataFetcher(private val sourceResolver: SourceResolver, val key : String): DataFetcher<Any> {
    override fun get(environment: DataFetchingEnvironment): Any? {
        val resolvedSourceObject = sourceResolver(environment)
        if(resolvedSourceObject is Map<*, *>) {
            return resolvedSourceObject[key]
        } else {
            throw RuntimeException("PropertyMapResolverDataFetcher attempt to fetch a field from an object instance that was not a map")
        }
    }
}
