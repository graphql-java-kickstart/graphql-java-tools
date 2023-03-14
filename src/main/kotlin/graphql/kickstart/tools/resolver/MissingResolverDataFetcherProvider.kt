package graphql.kickstart.tools.resolver

import graphql.kickstart.tools.SchemaParserOptions
import graphql.language.FieldDefinition
import graphql.schema.DataFetcher

/**
 * Provider for missing resolver data fetchers.
 */
fun interface MissingResolverDataFetcherProvider {
   fun createDataFetcher(field: FieldDefinition,
                         options: SchemaParserOptions
    ): DataFetcher<*>
}