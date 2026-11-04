package scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser

object DirectivesInvalid : Scenario {
    override val name = "directives-invalid"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/directives-invalid/schema.graphqls")
        .resolvers(DirectivesInvalidQuery())
        .directive("uppercase", UppercaseDirective())
}

class DirectivesInvalidQuery : GraphQLQueryResolver {
    fun motto() = "keep it simple"

    fun secret() = "42"

    fun members() = listOf(Member("m1", "ada"))
}
