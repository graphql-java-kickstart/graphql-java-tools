package graphql.kickstart.tools.resolver

import graphql.kickstart.tools.MissingResolverInfo
import graphql.kickstart.tools.SchemaParserOptions
import graphql.kickstart.tools.TypeClassMatcher
import graphql.language.FieldDefinition
import graphql.schema.DataFetcher

internal class MissingFieldResolver(
    field: FieldDefinition,
    options: SchemaParserOptions
) : FieldResolver(field, FieldResolverScanner.Search(Any::class.java, MissingResolverInfo(), null), options, Any::class.java) {

    override fun scanForMatches(): List<TypeClassMatcher.PotentialMatch> = listOf()

    override fun createDataFetcher(): DataFetcher<*> = DataFetcher<Any> { TODO("Schema resolver not implemented") }
}
