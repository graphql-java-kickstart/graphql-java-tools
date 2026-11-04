package graphql.kickstart.tools.compat.scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParserBuilder

object DirectivesInvalid : Scenario {
    override val name = "directives-invalid"

    override val files = listOf("compat/scenarios/directives-invalid/schema.graphqls")
    override val resolvers = listOf(DirectivesInvalidQuery())

    override fun configure(builder: SchemaParserBuilder) = builder
        .directive("uppercase", UppercaseDirective())
}

class DirectivesInvalidQuery : GraphQLQueryResolver {
    fun motto() = "keep it simple"

    fun secret() = "42"

    fun members() = listOf(Member("m1", "ada"))
}
