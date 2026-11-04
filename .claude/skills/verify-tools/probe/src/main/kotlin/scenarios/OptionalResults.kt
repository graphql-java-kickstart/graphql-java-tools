package scenarios

import graphql.GraphQLError
import graphql.execution.DataFetcherResult
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser
import java.util.Optional

/**
 * A DataFetcherResult inside an Optional: its data, its errors, an empty Optional, the same object type returned another way,
 * a list, and DataFetcherResult nested in itself under an Optional, which the build rejects like
 * DataFetcherResult<DataFetcherResult<T>>.
 */
object OptionalResults : Scenario {
    override val name = "optional-results"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/optional-results/schema.graphqls")
        .resolvers(OptionalResultsQuery())
}

object OptionalResultsMixed : Scenario {
    override val name = "optional-results-mixed"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/optional-results-mixed/schema.graphqls")
        .resolvers(OptionalResultsMixedQuery())
}

object OptionalResultsList : Scenario {
    override val name = "optional-results-list"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/optional-results-list/schema.graphqls")
        .resolvers(OptionalResultsListQuery())
}

object OptionalResultsNested : Scenario {
    override val name = "optional-results-nested"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/optional-results-nested/schema.graphqls")
        .resolvers(OptionalResultsNestedQuery())
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
