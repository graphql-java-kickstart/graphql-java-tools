package graphql.kickstart.tools.resolver

import graphql.kickstart.tools.MissingFieldResolverHandler
import graphql.kickstart.tools.MissingResolverInfo
import graphql.kickstart.tools.SchemaParserOptions
import graphql.kickstart.tools.TypeClassMatcher
import graphql.language.FieldDefinition
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment

internal class MissingFieldResolver(
    field: FieldDefinition,
    options: SchemaParserOptions
) : FieldResolver(field, FieldResolverScanner.Search(Any::class.java, MissingResolverInfo(), null), options, Any::class.java) {

    override fun scanForMatches(): List<TypeClassMatcher.PotentialMatch> = listOf()
    override fun createDataFetcher(): DataFetcher<*> {
        val handler = options.missingFieldResolverHandler ?: return NotImplementedMissingFieldDataFetcher()
        return MissingFieldDataFetcher(handler)
    }

    class MissingFieldDataFetcher(private val handler: MissingFieldResolverHandler) : DataFetcher<Any?> {
        override fun get(env: DataFetchingEnvironment?): Any? {
            return handler.resolve(env)
        }
    }

    class NotImplementedMissingFieldDataFetcher : DataFetcher<Any?> {
        override fun get(env: DataFetchingEnvironment?): Any? {
            TODO("Schema resolver not implemented")
        }
    }
}
