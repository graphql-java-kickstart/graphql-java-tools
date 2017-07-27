package com.coxautodev.graphql.tools

import graphql.language.FieldDefinition
import graphql.schema.DataFetcher

/**
 * @author Andrew Potter
 */
internal class PropertyFieldResolver(field: FieldDefinition, search: FieldResolverScanner.Search): FieldResolver(field, search) {
    override fun createDataFetcher(): DataFetcher<*> {
        TODO("not implemented")
    }

    override fun scanForMatches(): List<TypeClassMatcher.PotentialMatch> {
        TODO("not implemented")
    }
}
