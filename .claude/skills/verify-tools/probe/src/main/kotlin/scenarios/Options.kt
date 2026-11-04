package scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.resolver.MissingResolverDataFetcherProvider
import graphql.kickstart.tools.SchemaParser
import graphql.kickstart.tools.SchemaParserOptions
import graphql.schema.DataFetcher

/** A missing resolver provider standing in for a field nothing implements. */
object Options : Scenario {
    override val name = "options"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/options/schema.graphqls")
        .resolvers(OptionsQuery())
        .options(
            SchemaParserOptions.newOptions()
                .missingResolverDataFetcherProvider(MissingResolverDataFetcherProvider { field, _ ->
                    DataFetcher { "no resolver for ${field.name}" }
                })
                .build()
        )
}

class OptionsQuery : GraphQLQueryResolver {
    fun name() = "tools"
}
