package graphql.kickstart.tools

import graphql.GraphQL
import graphql.kickstart.tools.resolver.FieldResolverError
import graphql.kickstart.tools.resolver.MissingResolverDataFetcherProvider
import graphql.language.FieldDefinition
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

    @Test
    fun `should call missing resolver data fetcher provider if provided`() {
        val missingResolverDataFetcherProvider = TestMissingResolverDataFetcherProvider();
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
                    .missingResolverDataFetcherProvider(missingResolverDataFetcherProvider)
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

        assertEquals(missingResolverDataFetcherProvider.field?.name, "missingField")
        assertEquals(missingResolverDataFetcherProvider.options, options)
    }

    class TestMissingResolverDataFetcher : DataFetcher<Any?> {
        override fun get(env: DataFetchingEnvironment?): Any? {
            return env?.getArgument("input")
        }
    }

    class TestMissingResolverDataFetcherProvider : MissingResolverDataFetcherProvider {
        var field: FieldDefinition? = null
        var options: SchemaParserOptions? = null

        override fun createDataFetcher(field: FieldDefinition, options: SchemaParserOptions): DataFetcher<*> {
            this.field = field;
            this.options = options;
            return TestMissingResolverDataFetcher()
        }
    }
}
