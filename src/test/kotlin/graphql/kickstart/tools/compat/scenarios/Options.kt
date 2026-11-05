package graphql.kickstart.tools.compat.scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.resolver.MissingResolverDataFetcherProvider
import graphql.kickstart.tools.SchemaParserBuilder
import graphql.kickstart.tools.SchemaParserOptions
import graphql.schema.DataFetcher

object Options : Scenario {
    override val name = "options"

    override val files = listOf("compat/scenarios/options/schema.graphqls")
    override val resolvers = listOf(OptionsQuery())

    override fun configure(builder: SchemaParserBuilder) = builder
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
