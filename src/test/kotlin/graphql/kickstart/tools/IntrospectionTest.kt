package graphql.kickstart.tools

import graphql.GraphQL
import org.junit.Assert.assertEquals
import org.junit.Test

class IntrospectionTest {

    private fun graphQL(): GraphQL {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    name: String!
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun name() = "tools"
            })
            .build()
            .makeExecutableSchema()
            .assertEveryFieldResolves()
        return GraphQL.newGraphQL(schema).build()
    }

    @Test
    fun `introspection is enabled by default`() {
        val data = assertNoGraphQlErrors(graphQL()) {
            "{ __schema { queryType { name } } }"
        }

        assertEquals(mapOf("queryType" to mapOf("name" to "Query")), data["__schema"])
    }
}
