package graphql.kickstart.tools.compat.scenarios

import graphql.kickstart.tools.GraphQLMutationResolver
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParserBuilder
import graphql.kickstart.tools.SchemaParserOptions
import graphql.schema.DataFetchingEnvironment
import java.util.Optional

object Inputs : Scenario {
    override val name = "inputs"

    override val files = listOf("compat/scenarios/inputs/schema.graphqls")
    override val resolvers = listOf(InputsQuery(), InputsMutation())

    override fun configure(builder: SchemaParserBuilder) = builder
        .options(SchemaParserOptions.newOptions().inputArgumentOptionalDetectOmission(true).build())
}

enum class Genre { FICTION, SCIENCE, HISTORY }

data class BookInput(val title: String, val genre: Genre, val tags: List<String>?, val details: DetailsInput?)

data class DetailsInput(val pages: Int, val language: String)

data class SavedBook(val title: String, val genre: Genre, val tags: List<String>, val pages: Int?, val language: String?)

class InputsQuery : GraphQLQueryResolver {
    fun search(term: String, limit: Int, genres: List<Genre>?, env: DataFetchingEnvironment): String =
        "term=$term limit=$limit genres=$genres field=${env.field.name}"

    fun nickname(value: Optional<String>?): String = when {
        value == null -> "omitted"
        value.isEmpty -> "explicit null"
        else -> "value ${value.get()}"
    }
}

class InputsMutation : GraphQLMutationResolver {
    fun addBook(input: BookInput): SavedBook =
        SavedBook(input.title, input.genre, input.tags ?: emptyList(), input.details?.pages, input.details?.language)
}
