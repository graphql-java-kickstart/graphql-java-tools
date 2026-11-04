package scenarios

import graphql.GraphQLContext
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser
import graphql.kickstart.tools.SchemaParserOptions
import graphql.schema.Coercing
import graphql.schema.CoercingSerializeException
import graphql.schema.GraphQLScalarType
import org.apache.commons.lang3.reflect.TypeUtils
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

object NestedWrappersExact : Scenario {
    override val name = "nested-wrappers-exact"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/nested-wrappers-exact/schema.graphqls")
        .resolvers(NestedWrappersExactQuery())
        .scalars(dateTime)
        .options(SchemaParserOptions.newOptions().genericWrappers(tryWrapper, batchWrapper, boxWrapper, specialBoxWrapper, laterWrapper).build())

    private val tryWrapper = SchemaParserOptions.GenericWrapper.withTransformer(Try::class, 0, { t -> t.get() })

    private val batchWrapper = SchemaParserOptions.GenericWrapper.withTransformer(NestedWrappersExactBatch::class, 0, { batch -> batch.items }) { item ->
        TypeUtils.parameterize(List::class.java, TypeUtils.parameterize(CompletableFuture::class.java, item))
    }

    private val boxWrapper = SchemaParserOptions.GenericWrapper.withTransformer(NestedWrappersExactBox::class, 0, { box -> box.content })

    private val specialBoxWrapper = SchemaParserOptions.GenericWrapper.withTransformer(
        NestedWrappersExactSpecialBox::class, 0, { _ -> throw IllegalStateException("unwrapped by the value's class") }
    )

    private val laterWrapper = SchemaParserOptions.GenericWrapper.withTransformer(
        NestedWrappersExactLater::class, 0, { later -> FutureTask { later.value }.apply { run() } }
    )

    private val dateTime = GraphQLScalarType.newScalar()
        .name("DateTime")
        .coercing(object : Coercing<OffsetDateTime, String> {
            override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String =
                (dataFetcherResult as? OffsetDateTime)?.toString()
                    ?: throw CoercingSerializeException("Expected an OffsetDateTime but got a ${dataFetcherResult.javaClass.name}")
        })
        .build()
}

class NestedWrappersExactBatch<T>(val items: List<CompletableFuture<T>>)

open class NestedWrappersExactBox<T>(val content: T)

class NestedWrappersExactSpecialBox<T>(content: T) : NestedWrappersExactBox<T>(content)

class NestedWrappersExactLater<T>(val value: T)

class NestedWrappersExactQuery : GraphQLQueryResolver {
    private val first = OffsetDateTime.parse("2026-10-23T12:00Z")
    private val second = OffsetDateTime.parse("2026-10-24T12:00Z")

    fun dates(): List<CompletableFuture<OffsetDateTime>> = listOf(completedFuture(first), CompletableFuture.supplyAsync { second })

    fun triedDates(): List<Try<OffsetDateTime>> = listOf(Try.Success(first), Try.Success(second))

    fun plainFutureItems(): List<Future<NestedItem>> = listOf(FutureTask { NestedItem("f1") }.apply { run() })

    fun batchedItems(): NestedWrappersExactBatch<NestedItem> =
        NestedWrappersExactBatch(listOf(completedFuture(NestedItem("b1")), CompletableFuture.supplyAsync { NestedItem("b2") }))

    fun boxedItems(): List<NestedWrappersExactBox<NestedItem>> =
        listOf(NestedWrappersExactBox(NestedItem("x1")), NestedWrappersExactSpecialBox(NestedItem("x2")))

    fun laterItems(): List<NestedWrappersExactLater<NestedItem>> = listOf(NestedWrappersExactLater(NestedItem("l1")))

    suspend fun suspendPlainFutureItems(): List<Future<NestedItem>> = plainFutureItems()
}
