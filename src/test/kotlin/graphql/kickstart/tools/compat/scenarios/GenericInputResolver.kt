package graphql.kickstart.tools.compat.scenarios

import graphql.kickstart.tools.GraphQLQueryResolver

object GenericInputResolver : Scenario {
    override val name = "generic-input-resolver"

    override val files = listOf("compat/scenarios/generic-input-resolver/schema.graphqls")
    override val resolvers = listOf(GreetingQuery())
}

data class GreetingInput(val name: String)

abstract class InputResolver<I> : GraphQLQueryResolver {
    fun generic(input: I): String = "generic got $input"
}

class GreetingQuery : InputResolver<GreetingInput>() {
    fun plain(input: GreetingInput): String = "plain got $input"
}
