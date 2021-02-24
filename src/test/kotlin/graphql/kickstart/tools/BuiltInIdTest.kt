package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLSchema
import org.junit.Test
import java.util.*

class BuiltInIdTest {

    private val schema: GraphQLSchema = SchemaParser.newParser()
        .schemaString(
            """
            type Query {
                itemByLongId(id: ID!): Item1!
                itemsByLongIds(ids: [ID!]!): [Item1!]!
                itemByUuidId(id: ID!): Item2!
                itemsByUuidIds(ids: [ID!]!): [Item2!]!
            }
            
            type Item1 {
                id: ID!
            }
            
            type Item2 {
                id: ID!
            }
            """)
        .resolvers(QueryWithLongItemResolver())
        .build()
        .makeExecutableSchema()
    private val gql: GraphQL = GraphQL.newGraphQL(schema)
        .queryExecutionStrategy(AsyncExecutionStrategy())
        .build()

    @Test
    fun `supports Long as ID as input`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                itemByLongId(id: 1) {
                    id
                }
            }
            """
        }

        assert(data["itemByLongId"] != null)
        assert((data["itemByLongId"] as Map<*, *>)["id"] == "1")
    }

    @Test
    fun `supports list of Long as ID as input`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                itemsByLongIds(ids: [1,2,3]) {
                    id
                }   
            }
            """
        }

        assert(data["itemsByLongIds"] != null)
        assert(((data["itemsByLongIds"] as List<*>).size == 3))
        assert(((data["itemsByLongIds"] as List<*>)[0] as Map<*, *>)["id"] == "1")
    }

    @Test
    fun `supports UUID as ID as input`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                itemByUuidId(id: "00000000-0000-0000-0000-000000000000") {
                    id
                }
            }
            """
        }

        assert(data["itemByUuidId"] != null)
        assert((data["itemByUuidId"] as Map<*, *>)["id"] == "00000000-0000-0000-0000-000000000000")
    }

    @Test
    fun `supports list of UUID as ID as input`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                itemsByUuidIds(ids: ["00000000-0000-0000-0000-000000000000","11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222"]) {
                    id
                }
            }
            """
        }

        assert(data["itemsByUuidIds"] != null)
        assert(((data["itemsByUuidIds"] as List<*>).size == 3))
        assert(((data["itemsByUuidIds"] as List<*>)[0] as Map<*, *>)["id"] == "00000000-0000-0000-0000-000000000000")
    }

    class QueryWithLongItemResolver : GraphQLQueryResolver {
        fun itemByLongId(id: Long): Item1 = Item1(id)
        fun itemsByLongIds(ids: List<Long>): List<Item1> = ids.map { Item1(it) }
        fun itemByUuidId(id: UUID): Item2 = Item2(id)
        fun itemsByUuidIds(ids: List<UUID>): List<Item2> = ids.map { Item2(it) }
    }

    class Item1(var id: Long? = null)
    class Item2(var id: UUID? = null)
}
