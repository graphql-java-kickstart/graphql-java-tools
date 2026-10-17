package graphql.kickstart.tools

import graphql.GraphQL
import graphql.validation.ValidationError
import graphql.validation.ValidationErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntrospectionTest {

    private fun graphQL(introspectionEnabled: Boolean): GraphQL {
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
            .options(SchemaParserOptions.newOptions().introspectionEnabled(introspectionEnabled).build())
            .build()
            .makeExecutableSchema()
        return GraphQL.newGraphQL(schema).build()
    }

    @Test
    fun `introspection is enabled by default`() {
        val data = assertNoGraphQlErrors(graphQL(introspectionEnabled = true)) {
            "{ __schema { queryType { name } } }"
        }

        assertEquals(mapOf("queryType" to mapOf("name" to "Query")), data["__schema"])
    }

    @Test
    fun `disabling introspection hides the fields of introspection types`() {
        val gql = graphQL(introspectionEnabled = false)

        listOf("{ __schema { queryType { name } } }", "{ __type(name: \"Query\") { name } }").forEach { query ->
            val result = gql.execute(query)

            assertNull(result.getData())
            assertEquals(
                listOf(ValidationErrorType.FieldUndefined),
                result.errors.map { (it as ValidationError).validationErrorType })
        }
    }

    @Test
    fun `disabling introspection keeps __typename`() {
        val data = assertNoGraphQlErrors(graphQL(introspectionEnabled = false)) {
            "{ name __typename }"
        }

        assertEquals(mapOf("name" to "tools", "__typename" to "Query"), data)
    }
}
