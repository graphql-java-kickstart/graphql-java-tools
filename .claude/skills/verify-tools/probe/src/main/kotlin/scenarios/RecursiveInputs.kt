package scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser

object RecursiveInputs : Scenario {
    override val name = "recursive-inputs"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/recursive-inputs/schema.graphqls")
        .resolvers(RecursiveInputsQuery())
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
