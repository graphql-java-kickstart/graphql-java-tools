package scenarios

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.GraphQLResolver
import graphql.kickstart.tools.SchemaParser
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.GraphQLScalarType
import java.time.LocalDate
import java.util.Locale

object Bookstore : Scenario {
    override val name = "bookstore"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/bookstore/schema.graphqls")
        .resolvers(BookstoreQuery(), AuthorResolver())
        .scalars(dateScalar)
}

object BookstoreMissingResolver : Scenario {
    override val name = "bookstore-missing-resolver"

    override fun parser() = SchemaParser.newParser()
        .files("scenarios/bookstore/schema.graphqls", "scenarios/bookstore-missing-resolver/extension.graphqls")
        .resolvers(BookstoreQuery(), AuthorResolver())
        .scalars(dateScalar)
}

data class Author(val id: String, val name: String)

class Book(val id: String, val title: String, val authorId: String, val published: LocalDate, private val stock: Int) {
    fun isInStock() = stock > 0
}

private val authors = listOf(Author("a1", "Ursula K. Le Guin"), Author("a2", "Iain M. Banks"))
private val books = listOf(
    Book("b1", "The Dispossessed", "a1", LocalDate.of(1974, 5, 1), 3),
    Book("b2", "The Left Hand of Darkness", "a1", LocalDate.of(1969, 3, 1), 0),
    Book("b3", "Use of Weapons", "a2", LocalDate.of(1990, 9, 1), 1),
)

class BookstoreQuery : GraphQLQueryResolver {
    fun books(): List<Book> = books
    fun book(id: String): Book? = books.find { it.id == id }
}

class AuthorResolver : GraphQLResolver<Book> {
    fun author(book: Book): Author = authors.first { it.id == book.authorId }
}

val dateScalar: GraphQLScalarType = GraphQLScalarType.newScalar()
    .name("Date")
    .description("ISO-8601 date")
    .coercing(object : Coercing<LocalDate, String> {
        override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale) =
            (dataFetcherResult as LocalDate).toString()

        override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): LocalDate =
            LocalDate.parse(input as String)

        override fun parseLiteral(input: Value<*>, variables: CoercedVariables, graphQLContext: GraphQLContext, locale: Locale): LocalDate =
            LocalDate.parse((input as StringValue).value)
    })
    .build()
