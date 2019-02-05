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

    /**
     * this impl still has some problems:
     * - mapClass lost its generics somewhere along the way, so if you had
     *   something like {@code class Resolver<V> implements Map<String, V>}
     *   then we won't be able to infer the value type
     * - this doesn't handle the case of the map value type being generic,
     *   for example {@code class Resolver implements Map<String, MyType<MyTypeArg>>}
     */
    fun getMapGenericType(mapClass : JavaType) : JavaType {
        val resolvedType = TypeResolver().resolve(mapClass)

        val mapValueType = resolvedType.typeParametersFor(Map::class.java)[1]
        if (mapValueType.typeParameters.isEmpty()) {
            // no type params should mean regular class
            return mapValueType.erasedType
        } else {
            // TODO raise an error? try to handle this case?
            return Object::class.java
        }
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
