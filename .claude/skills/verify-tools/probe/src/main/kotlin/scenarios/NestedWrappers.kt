package scenarios

import graphql.GraphQL
import graphql.execution.DefaultValueUnboxer
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser
import graphql.kickstart.tools.SchemaParserOptions
import graphql.ExecutionInput
import graphql.schema.DataFetchingEnvironment
import org.dataloader.BatchLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture

object NestedWrappers : Scenario {
    override val name = "nested-wrappers"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/nested-wrappers/schema.graphqls")
        .resolvers(NestedWrappersQuery())
        .options(SchemaParserOptions.newOptions().genericWrappers(tryWrapper).build())

    override fun input(builder: ExecutionInput.Builder): ExecutionInput.Builder = builder.dataLoaderRegistry(
        DataLoaderRegistry().apply {
            register("items", DataLoaderFactory.newDataLoader(BatchLoader<String, NestedItem> { keys -> completedFuture(keys.map { NestedItem(it) }) }))
        }
    )

    private val tryWrapper = SchemaParserOptions.GenericWrapper.withTransformer(Try::class, 0, { t -> t.get() })
}

object NestedWrappersUnboxer : Scenario by NestedWrappers {
    override val name = "nested-wrappers-unboxer"

    override fun graphQL(builder: GraphQL.Builder): GraphQL.Builder = builder.valueUnboxer { value ->
        val unwrapped = when (value) {
            is Try<*> -> value.get()
            is CompletableFuture<*> -> value.join()
            else -> value
        }
        unwrapped?.let { DefaultValueUnboxer.unboxValue(it) }
    }
}

sealed class Try<out T> {
    abstract fun get(): T

    data class Success<T>(val value: T) : Try<T>() {
        override fun get() = value
    }

    data class Failure(val cause: Throwable) : Try<Nothing>() {
        override fun get(): Nothing = throw cause
    }
}

data class Payload(val data: String)

data class NestedItem(val id: String)

class NestedWrappersQuery : GraphQLQueryResolver {
    fun payload(): Try<Payload> = Try.Success(Payload("single"))

    fun payloads(): List<Try<Payload>> = listOf(Try.Success(Payload("a")), Try.Success(Payload("b")))

    fun payloadStrings(): List<Try<String>> = listOf(Try.Success("a"), Try.Success("b"))

    fun items(): List<CompletableFuture<NestedItem>> = listOf(completedFuture(NestedItem("1")), CompletableFuture.supplyAsync { NestedItem("2") })

    fun itemNames(): List<CompletableFuture<String>> = listOf(completedFuture("one"), CompletableFuture.supplyAsync { "two" })

    fun futureOfFuture(): CompletableFuture<CompletableFuture<NestedItem>> = CompletableFuture.supplyAsync { completedFuture(NestedItem("inner")) }

    fun futureOfTries(): CompletableFuture<List<Try<Payload>>> = completedFuture(payloads())

    fun triesOfFutures(): List<Try<CompletableFuture<NestedItem>>> = items().map { Try.Success(it) }

    fun loadedItems(env: DataFetchingEnvironment): List<CompletableFuture<NestedItem>> =
        listOf("x", "y").map { env.getDataLoader<String, NestedItem>("items")!!.load(it) }

    fun failedTryInList(): List<Try<Payload>> = listOf(Try.Success(Payload("ok")), Try.Failure(IllegalStateException("payload failed")))

    fun failedFutureInList(): List<CompletableFuture<NestedItem>> =
        listOf(completedFuture(NestedItem("ok")), CompletableFuture.failedFuture(IllegalStateException("item failed")))

    fun plainItems(): List<NestedItem> = listOf(NestedItem("p1"), NestedItem("p2"))

    fun futureOfItems(): CompletableFuture<List<NestedItem>> = completedFuture(plainItems())
}

object NestedWrappersSuspend : Scenario {
    override val name = "nested-wrappers-suspend"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/nested-wrappers-suspend/schema.graphqls")
        .resolvers(NestedWrappersSuspendQuery())
}

class NestedWrappersSuspendQuery : GraphQLQueryResolver {
    suspend fun nullableItems(): List<CompletableFuture<NestedItem>> = listOf(completedFuture(NestedItem("1")), CompletableFuture.supplyAsync { NestedItem("2") })

    suspend fun nonNullItems(): List<CompletableFuture<NestedItem>> = nullableItems()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> typeVariableStrings(): List<T> = listOf("a", "b") as List<T>
}
