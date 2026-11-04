package scenarios

import graphql.Scalars
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment

object ScalarDirectives : Scenario {
    override val name = "scalar-directives"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/scalar-directives/schema.graphqls")
        .resolvers(ScalarDirectivesQuery())
        .scalars(
            string("Date"),
            GraphQLScalarType.newScalar(string("DateTime")).description("An RFC-3339 date-time")
                .specifiedByUrl("https://scalars.graphql.org/andimarek/date-time").build(),
            string("Money")
        )
        .directive("since", ArgumentWiring("date"))
        .directive("unit", ArgumentWiring("name"))

    private fun string(name: String) = GraphQLScalarType.newScalar().name(name).coercing(Scalars.GraphQLString.coercing).build()
}

class ArgumentWiring(private val argument: String) : SchemaDirectiveWiring {
    override fun onScalar(environment: SchemaDirectiveWiringEnvironment<GraphQLScalarType>): GraphQLScalarType {
        val directive = environment.appliedDirective
        val value = directive.getArgument(argument)?.getValue<String>()
        return environment.element.transform { it.description("${environment.element.description}, wired with @${directive.name}($value)") }
    }
}

class ScalarDirectivesQuery : GraphQLQueryResolver {
    fun today(): String = "2026-10-19"
    fun now(): String = "2026-10-19T12:00:00Z"
    fun shift(from: String?): String? = from
    fun price(): String = "9.99"
}
