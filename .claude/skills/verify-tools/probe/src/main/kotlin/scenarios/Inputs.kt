package scenarios

import graphql.kickstart.tools.GraphQLMutationResolver
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser
import graphql.kickstart.tools.SchemaParserOptions
import graphql.schema.DataFetchingEnvironment
import java.util.Optional

/** Scalar, list, enum and input object arguments, SDL default values, DataFetchingEnvironment and omitted vs null. */
object Inputs : Scenario {
    override val name = "inputs"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/inputs/schema.graphqls")
        .resolvers(InputsQuery(), InputsMutation())
        .options(SchemaParserOptions.newOptions().inputArgumentOptionalDetectOmission(true).build())
}

enum class Genre { FICTION, SCIENCE, HISTORY }

data class BookInput(val title: String, val genre: Genre, val tags: List<String>?, val details: DetailsInput?)

data class DetailsInput(val pages: Int, val language: String)

data class SavedBook(val title: String, val genre: Genre, val tags: List<String>, val pages: Int?, val language: String?)

class InputsQuery : GraphQLQueryResolver {
    fun search(term: String, limit: Int, genres: List<Genre>?, env: DataFetchingEnvironment): String =
        "term=$term limit=$limit genres=$genres field=${env.field.name}"

    /** null when the argument was omitted, Optional.empty() when it was passed as null. */
    @Suppress("OptionalAssignedToNull")
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
