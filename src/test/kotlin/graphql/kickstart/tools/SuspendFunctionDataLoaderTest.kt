package graphql.kickstart.tools

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.future.await
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderRegistry
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SuspendFunctionDataLoaderTest {

    private val schema = SchemaParser.newParser()
        .schemaString(
            """
            type Query {
                user(id: Int!): User!
                users(ids: [Int!]!): [User!]!
            }

            type User {
                id: Int!
                friend: User!
            }
            """)
        .resolvers(Query(), UserResolver())
        .build()
        .makeExecutableSchema()
    private val gql = GraphQL.newGraphQL(schema).build()

    @Test
    fun `root suspend function can await a data loader`() {
        repeat(20) {
            val data = execute("{ users(ids: [1, 2, 3]) { id } }")

            assertEquals(data, mapOf("users" to listOf(mapOf("id" to 1), mapOf("id" to 2), mapOf("id" to 3))))
        }
    }

    @Test
    fun `nested suspend function can await a data loader`() {
        repeat(20) {
            val data = execute("{ a: user(id: 1) { friend { id } } b: user(id: 2) { friend { id } } }")

            assertEquals(data, mapOf(
                "a" to mapOf("friend" to mapOf("id" to 2)),
                "b" to mapOf("friend" to mapOf("id" to 3))
            ))
        }
    }

    private fun execute(query: String): Any? {
        val userLoader = DataLoaderFactory.newDataLoader<Int, User> { ids ->
            CompletableFuture.supplyAsync { ids.map { User(it) } }
        }
        val registry = DataLoaderRegistry.newRegistry().register("user", userLoader).build()

        // a resolver that awaits a load before it is dispatched hangs, so don't wait forever
        val result = gql.executeAsync(ExecutionInput.newExecutionInput(query).dataLoaderRegistry(registry))
            .get(5, TimeUnit.SECONDS)

        assert(result.errors.isEmpty()) { result.errors.joinToString { it.message } }
        return result.getData<Any>()
    }

    class Query : GraphQLQueryResolver {
        fun user(id: Int): User = User(id)

        suspend fun users(ids: List<Int>, env: DataFetchingEnvironment): List<User> =
            env.getDataLoader<Int, User>("user")!!.loadMany(ids).await()
    }

    class UserResolver : GraphQLResolver<User> {
        suspend fun friend(user: User, env: DataFetchingEnvironment): User =
            env.getDataLoader<Int, User>("user")!!.load(user.id + 1).await()
    }

    data class User(val id: Int)
}
