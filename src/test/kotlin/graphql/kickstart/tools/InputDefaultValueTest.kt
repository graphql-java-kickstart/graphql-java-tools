package graphql.kickstart.tools

import graphql.GraphQL
import org.junit.Test

class InputDefaultValueTest {

    private val schema = SchemaParser.newParser()
        .schemaString(
            """
            type Query {
                list(listing: Listing! = { size: 10 }): String!
                listWithEmptyDefault(listing: Listing! = {}): String!
            }

            input Listing {
                size: Int! = 10
                after: String
            }
            """)
        .resolvers(QueryResolver())
        .build()
        .makeExecutableSchema()
    private val gql = GraphQL.newGraphQL(schema).build()

    @Test
    fun `input field default value is applied when the field is omitted`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                list(listing: { after: "test" })
            }
            """
        }

        assertEquals(data["list"], "size: 10, after: test")
    }

    @Test
    fun `input field default value is applied to an argument default value`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                listWithEmptyDefault
            }
            """
        }

        assertEquals(data["listWithEmptyDefault"], "size: 10, after: null")
    }

    class QueryResolver : GraphQLQueryResolver {
        fun list(listing: Listing): String = describe(listing)
        fun listWithEmptyDefault(listing: Listing): String = describe(listing)

        private fun describe(listing: Listing) = "size: ${listing.size}, after: ${listing.after}"
    }

    class Listing {
        var size: Int? = null
        var after: String? = null
    }
}
