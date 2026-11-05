package graphql.kickstart.tools.compat.scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParserBuilder

object NestedUnions : Scenario {
    override val name = "nested-unions"

    override val files = listOf("compat/scenarios/nested-unions/schema.graphqls")
    override val resolvers = listOf(NestedUnionsQuery())

    override fun configure(builder: SchemaParserBuilder) = builder
        .dictionary(NestedUnionsQuery.Book::class.java, NestedUnionsQuery.Movie::class.java)
}

class NestedUnionsQuery : GraphQLQueryResolver {
    fun results(): List<Any> = listOf(Book("Solaris"), Movie("Stalker"))

    data class Book(val title: String)

    data class Movie(val title: String)
}
