package com.coxautodev.graphql.tools

import graphql.language.FieldDefinition
import graphql.schema.DataFetcher

/**
 * @author Andrew Potter
 */
internal abstract class FieldResolver(val field: FieldDefinition, val search: FieldResolverScanner.Search) {
    val resolverInfo: ResolverInfo = search.resolverInfo

    abstract fun scanForMatches(): List<TypeClassMatcher.PotentialMatch>
    abstract fun createDataFetcher(): DataFetcher<*>
}
