package scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser

/**
 * A union that is only a member of another union. No field returns `Media`.
 */
object NestedUnions : Scenario {
    override val name = "nested-unions"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/nested-unions/schema.graphqls")
        .resolvers(NestedUnionsQuery())
        .dictionary(NestedUnionsQuery.Book::class.java, NestedUnionsQuery.Movie::class.java)
}

class NestedUnionsQuery : GraphQLQueryResolver {
    fun results(): List<Any> = listOf(Book("Solaris"), Movie("Stalker"))

    data class Book(val title: String)

    data class Movie(val title: String)
}
