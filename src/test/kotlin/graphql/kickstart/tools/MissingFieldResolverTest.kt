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
    fun `should warn about each missing field when unimplemented resolvers are allowed`() {
        val (schema, warnings) = withWarnings {
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
                .options(SchemaParserOptions.newOptions().allowUnimplementedResolvers(true).build())
                .build()
                .makeExecutableSchema()
        }
        // missingField has no resolver, so querying it throws
        assertEveryFieldResolves(schema, except = setOf("Query.missingField"))

        assertEquals(warnings, listOf(
            "Option 'allowUnimplementedResolvers' should only be set to true during development, as it can cause schema errors to be moved " +
                "to query time instead of schema creation time.  Make sure this is turned off in production.",
            "Missing resolver for field: ${schema.queryType.getFieldDefinition("missingField").definition}"
        ))
    }

    @Test
    @Suppress("DEPRECATION")
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
        assertEveryFieldResolves(schema)

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
        val options = SchemaParserOptions.newOptions()
                .missingResolverDataFetcherProvider(missingResolverDataFetcherProvider)
                .build();
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
            .options(options)
            .build()
            .makeExecutableSchema()
        assertEveryFieldResolves(schema)

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
