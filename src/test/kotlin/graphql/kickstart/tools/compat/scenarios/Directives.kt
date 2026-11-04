package graphql.kickstart.tools.compat.scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParserBuilder
import graphql.relay.Connection
import graphql.relay.SimpleListConnection
import graphql.schema.DataFetcherFactories
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment

object Directives : Scenario {
    override val name = "directives"

    override val files = listOf("compat/scenarios/directives/schema.graphqls")
    override val resolvers = listOf(DirectivesQuery())

    override fun configure(builder: SchemaParserBuilder) = builder
        .directive("uppercase", UppercaseDirective())
}

data class Member(val id: String, val name: String)

private val members = listOf(Member("m1", "ada"), Member("m2", "grace"), Member("m3", "barbara"))

class DirectivesQuery : GraphQLQueryResolver {
    fun motto() = "keep it simple"

    fun oldMotto() = "move fast"

    fun members(first: Int?, after: String?, env: DataFetchingEnvironment): Connection<Member> =
        SimpleListConnection(members).get(env)
}

class UppercaseDirective : SchemaDirectiveWiring {
    override fun onField(environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>): GraphQLFieldDefinition {
        val original = environment.fieldDataFetcher
        environment.fieldDataFetcher = DataFetcherFactories.wrapDataFetcher(original) { _, value -> (value as? String)?.uppercase() }
        return environment.element
    }
}
