package scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser

object GenericInputResolver : Scenario {
    override val name = "generic-input-resolver"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/generic-input-resolver/schema.graphqls")
        .resolvers(GreetingQuery())
}

data class GreetingInput(val name: String)

abstract class InputResolver<I> : GraphQLQueryResolver {
    fun generic(input: I): String = "generic got $input"
}

class GreetingQuery : InputResolver<GreetingInput>() {
    fun plain(input: GreetingInput): String = "plain got $input"
}
