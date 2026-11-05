package graphql.kickstart.tools.compat.scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParserBuilder

object RecursiveInputs : Scenario {
    override val name = "recursive-inputs"

    override val files = listOf("compat/scenarios/recursive-inputs/schema.graphqls")
    override val resolvers = listOf(RecursiveInputsQuery())

    override fun configure(builder: SchemaParserBuilder) = builder
        .dictionary(RangeInput::class, LimitUnit::class)
}

data class RangeInput(val min: Int, val max: Int)

enum class LimitUnit { ITEMS, PAGES }

data class TreeFilter(val name: String?, val and: List<TreeFilter>?, val not: TreeFilter?, val child: ChildFilter?)

data class ChildFilter(val parent: TreeFilter?, val siblings: List<List<ChildFilter>>?, val depth: Int)

class RecursiveInputsQuery : GraphQLQueryResolver {
    fun describe(filter: TreeFilter): String = filter.toString()

    fun names(first: Int?): List<String> = listOf("a", "b", "c").take(first ?: 3)
}
