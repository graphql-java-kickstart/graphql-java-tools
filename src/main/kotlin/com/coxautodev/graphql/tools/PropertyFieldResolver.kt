package com.coxautodev.graphql.tools

import graphql.language.FieldDefinition
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import java.lang.reflect.Field

/**
 * @author Andrew Potter
 */
internal class PropertyFieldResolver(field: FieldDefinition, search: FieldResolverScanner.Search, options: SchemaParserOptions, val property: Field): FieldResolver(field, search, options, property.declaringClass) {
    override fun createDataFetcher(): DataFetcher<*> {
        return PropertyFieldResolverDataFetcher(getSourceResolver(), property)
    }

    override fun scanForMatches(): List<TypeClassMatcher.PotentialMatch> {
        return listOf(TypeClassMatcher.PotentialMatch.returnValue(field.type, property.genericType, genericType, SchemaClassScanner.FieldTypeReference(property), false))
    }

    override fun toString() = "PropertyFieldResolver{property=$property}"
}

class PropertyFieldResolverDataFetcher(private val sourceResolver: SourceResolver, private val field: Field): DataFetcher<Any> {
    override fun get(environment: DataFetchingEnvironment): Any? {
        return field.get(sourceResolver(environment))
    }
}
