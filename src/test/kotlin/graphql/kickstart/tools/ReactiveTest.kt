package graphql.kickstart.tools

import graphql.ExceptionWhileDataFetching
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.kickstart.tools.SchemaParser.Companion.newParser
import graphql.kickstart.tools.SchemaParserOptions.Companion.newOptions
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

//import io.reactivex.Single;
//import io.reactivex.internal.operators.single.SingleJust;
//import static io.reactivex.Maybe.just;
@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveTest {

    @Test
    fun futureSucceeds() {
        val options = newOptions() //                .genericWrappers(
            //                        new SchemaParserOptions.GenericWrapper(Single.class, 0),
            //                        new SchemaParserOptions.GenericWrapper(SingleJust.class, 0)
            //                )
            .build()

        val schema = newParser().file("Reactive.graphqls")
            .resolvers(Query())
            .options(options)
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()

        assertNoGraphQlErrors(gql) {
            "query { organization(organizationId: 1) { user { id } } }"
        }
    }

    @Test
    fun `future that is not a completion stage fails with a clear error`() {
        val result = GraphQL.newGraphQL(itemSchema(PlainFutureQuery())).build()
            .execute("query { item { id } itemWithArgument(id: 1) { id } name }")

        assertEquals(result.getData(), mapOf("item" to null, "itemWithArgument" to null, "name" to null))
        assertEquals(result.errorMessagesByPath(), listOf("item", "itemWithArgument", "name").associateWith {
            plainFutureError(PlainFutureQuery::class.java, it)
        })
    }

    @Test
    fun `suspend function that resolves to a future fails with a clear error`() {
        val result = GraphQL.newGraphQL(itemSchema(PlainFutureQuery())).build()
            .execute("query { suspendItem { id } suspendCompletableItem { id } }")

        assertEquals(result.getData(), mapOf("suspendItem" to null, "suspendCompletableItem" to null))
        assertEquals(result.errorMessagesByPath(), mapOf(
            "suspendItem" to FutureTask::class.java,
            "suspendCompletableItem" to CompletableFuture::class.java
        ).mapValues { (name, futureClass) ->
            "Suspend function '${PlainFutureQuery::class.java.name}.$name' resolved to a ${futureClass.name}, which graphql-java can't wait on. Return its value instead."
        })
    }

    @Test
    fun `future that is not a completion stage can be unwrapped by a generic wrapper`() {
        val options = newOptions()
            .genericWrappers(GenericWrapper.withTransformer(FutureTask::class, 0, { task -> task.get() }))
            .build()
        val gql = GraphQL.newGraphQL(itemSchema(PlainFutureQuery(), options)).build()

        val data = assertNoGraphQlErrors(gql) {
            "query { item { id } itemWithArgument(id: 2) { id } name suspendItem { id } nullItem { id } }"
        }

        assertEquals(data, mapOf(
            "item" to mapOf("id" to "1"),
            "itemWithArgument" to mapOf("id" to "2"),
            "name" to "name",
            "suspendItem" to mapOf("id" to "3"),
            "nullItem" to null
        ))
    }

    @Test
    fun `future that a generic wrapper, a type variable or a union resolves to fails with a clear error`() {
        val schema = newParser()
            .schemaString(
                """
                type Query {
                    item: Item
                    boxedItem: Item
                    search: SearchResult
                }

                union SearchResult = Item

                type Item {
                    id: ID
                }
                """)
            .resolvers(FutureItemQuery())
            .options(newOptions()
                .genericWrappers(GenericWrapper.withTransformer(Box::class, 0, { box -> FutureTask { box.value }.also { it.run() } }))
                .build())
            .build()
            .makeExecutableSchema()

        val result = GraphQL.newGraphQL(schema).build().execute("query { item { id } boxedItem { id } search { ... on Item { id } } }")

        assertEquals(result.getData(), mapOf("item" to null, "boxedItem" to null, "search" to null))
        assertEquals(result.errorMessagesByPath(), mapOf(
            "item" to plainFutureError(ItemQuery::class.java, "item"),
            "boxedItem" to plainFutureError(FutureItemQuery::class.java, "boxedItem"),
            "search" to plainFutureError(FutureItemQuery::class.java, "search")
        ))
    }

    @Test
    fun `value that implements Future resolves normally`() {
        val schema = newParser()
            .schemaString(
                """
                type Query {
                    job: Job
                }

                type Job {
                    id: ID
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun job(): Job = Job(1)
            })
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            "query { job { id } }"
        }

        assertEquals(data, mapOf("job" to mapOf("id" to "1")))
    }

    @Test
    fun `future resolves as a value when no generic wrapper unwraps it`() {
        val schema = newParser()
            .schemaString(
                """
                type Query {
                    task: Task
                }

                type Task {
                    done: Boolean
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun task(): Future<String> = FutureTask { "done" }.also { it.run() }
            })
            .options(newOptions().useDefaultGenericWrappers(false).build())
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            "query { task { done } }"
        }

        assertEquals(data, mapOf("task" to mapOf("done" to true)))
    }

    private fun itemSchema(query: GraphQLQueryResolver, options: SchemaParserOptions = newOptions().build()) = newParser()
        .schemaString(
            """
            type Query {
                item: Item
                itemWithArgument(id: ID): Item
                name: String
                suspendItem: Item
                suspendCompletableItem: Item
                nullItem: Item
            }

            type Item {
                id: ID
            }
            """)
        .resolvers(query)
        .options(options)
        .build()
        .makeExecutableSchema()

    private fun plainFutureError(resolverClass: Class<*>, methodName: String) =
        "Resolver method '${resolverClass.name}.$methodName' resolved to a ${FutureTask::class.java.name}, which graphql-java can't wait on. " +
            "Return a CompletionStage, such as a CompletableFuture, from the method or its generic wrapper transformer instead."

    private fun ExecutionResult.errorMessagesByPath() =
        errors.associate { it.path?.joinToString(".") to (it as ExceptionWhileDataFetching).exception.message }

    // the futures are already done, so even a future that could be read without blocking is rejected
    private class PlainFutureQuery : GraphQLQueryResolver {
        fun item(): Future<Item> = FutureTask { Item(1) }.also { it.run() }
        fun itemWithArgument(id: Long): Future<Item> = FutureTask { Item(id) }.also { it.run() }
        fun name(): Future<String> = FutureTask { "name" }.also { it.run() }
        suspend fun suspendItem(): Future<Item> = FutureTask { Item(3) }.also { it.run() }
        suspend fun suspendCompletableItem(): Future<Item> = CompletableFuture.completedFuture(Item(4))
        fun nullItem(): Future<Item?> = FutureTask<Item?> { null }.also { it.run() }
    }

    open class ItemQuery<R>(private val item: () -> R) {
        fun item(): R = item.invoke()
    }

    private class FutureItemQuery : ItemQuery<Future<Item>>({ FutureTask { Item(1) }.also { it.run() } }), GraphQLQueryResolver {
        fun boxedItem(): Box<Item> = Box(Item(2))
        fun search(): Future<Any> = FutureTask<Any> { Item(3) }.also { it.run() }
    }

    private class Box<T>(val value: T)

    private class Item(private val id: Long)

    // implements Future, but is the schema type itself rather than a wrapper around one
    private class Job(private val id: Long) : Future<String> by CompletableFuture.completedFuture("done")

    private class Query : GraphQLQueryResolver {
        //        Single<Optional<Organization>> organization(int organizationid) {
        //            return Single.just(Optional.empty()); //CompletableFuture.completedFuture(null);
        //        }
        fun organization(organizationid: Int): Future<Optional<Organization>> {
            return CompletableFuture.completedFuture(Optional.of(Organization()))
        }
    }

    private class Organization {
        private val user: User? = null
    }

    private class User {
        private val id: Long? = null
        private val name: String? = null
    }
}
