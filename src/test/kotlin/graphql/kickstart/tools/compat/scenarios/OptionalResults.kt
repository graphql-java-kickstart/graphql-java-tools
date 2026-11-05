package graphql.kickstart.tools.compat.scenarios

import graphql.GraphQLError
import graphql.execution.DataFetcherResult
import graphql.kickstart.tools.GraphQLQueryResolver
import java.util.Optional

object OptionalResults : Scenario {
    override val name = "optional-results"

    override val files = listOf("compat/scenarios/optional-results/schema.graphqls")
    override val resolvers = listOf(OptionalResultsQuery())
}

object OptionalResultsMixed : Scenario {
    override val name = "optional-results-mixed"

    override val files = listOf("compat/scenarios/optional-results-mixed/schema.graphqls")
    override val resolvers = listOf(OptionalResultsMixedQuery())
}

object OptionalResultsList : Scenario {
    override val name = "optional-results-list"

    override val files = listOf("compat/scenarios/optional-results-list/schema.graphqls")
    override val resolvers = listOf(OptionalResultsListQuery())
}

object OptionalResultsNested : Scenario {
    override val name = "optional-results-nested"

    override val files = listOf("compat/scenarios/optional-results-nested/schema.graphqls")
    override val resolvers = listOf(OptionalResultsNestedQuery())
}

class OptionalResultsPayload(val data: String)

private fun <T : Any> optionalResult(data: T, vararg errors: String): Optional<DataFetcherResult<T>> = Optional.of(
    DataFetcherResult.newResult<T>().data(data).errors(errors.map { GraphQLError.newError().message(it).build() }).build()
)

class OptionalResultsQuery : GraphQLQueryResolver {
    fun optionalResult(): Optional<DataFetcherResult<OptionalResultsPayload>> = optionalResult(OptionalResultsPayload("a"), "partial")
    fun optionalResultName(): Optional<DataFetcherResult<String>> = optionalResult("a")
    fun emptyOptionalResult(): Optional<DataFetcherResult<OptionalResultsPayload>> = Optional.empty()
}

class OptionalResultsMixedQuery : GraphQLQueryResolver {
    fun optionalResult(): Optional<DataFetcherResult<OptionalResultsPayload>> = optionalResult(OptionalResultsPayload("a"), "partial")
    fun resultOfOptional(): DataFetcherResult<Optional<OptionalResultsPayload>> =
        DataFetcherResult.newResult<Optional<OptionalResultsPayload>>().data(Optional.of(OptionalResultsPayload("a"))).build()
}

class OptionalResultsListQuery : GraphQLQueryResolver {
    fun optionalResultList(): Optional<DataFetcherResult<List<OptionalResultsPayload>>> =
        optionalResult(listOf(OptionalResultsPayload("a"), OptionalResultsPayload("b")))
}

class OptionalResultsNestedQuery : GraphQLQueryResolver {
    fun optionalResultOfResult(): Optional<DataFetcherResult<DataFetcherResult<OptionalResultsPayload>>> =
        optionalResult(DataFetcherResult.newResult<OptionalResultsPayload>().data(OptionalResultsPayload("a")).build())
}
