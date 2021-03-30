package graphql.kickstart.tools

import graphql.GraphQL
import graphql.kickstart.tools.resolver.FieldResolverError
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.junit.Test
import java.util.*

class MissingFieldResolverTest {

    @Test(expected = FieldResolverError::class)
    fun `should throw error when a field is missing`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    implementedField(input: String): String
                    missingField(input: Int): Int
                }
                """
            )
            .resolvers(object : GraphQLQueryResolver {
                fun implementedField(input: Optional<String>) = input.toString()
            })
            .build()
            .makeExecutableSchema()
    }

    @Test
    fun `should call missing resolver data fetcher if provided`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    implementedField(input: String): String
                    missingField(input: Int): Int
                }
                """
            )
            .resolvers(object : GraphQLQueryResolver {
                fun implementedField(input: Optional<String>) = input.toString()
            })
            .options(SchemaParserOptions.newOptions()
                .missingResolverDataFetcher(TestMissingResolverDataFetcher())
                .build())
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema).build()

        val result = gql.execute(
            """
            query {
                implementedField(input: "test-value")
                missingField(input: 1)
            }
            """)

        val expected = mapOf(
            "implementedField" to "Optional[test-value]",
            "missingField" to 1
        )

        assertEquals(result.getData(), expected)
    }

    class TestMissingResolverDataFetcher : DataFetcher<Any?> {
        override fun get(env: DataFetchingEnvironment?): Any? {
            return env?.getArgument("input")
        }
    }
}
