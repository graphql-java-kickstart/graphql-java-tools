package graphql.kickstart.tools

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.kickstart.tools.resolver.FieldResolverError
import graphql.schema.DataFetchingEnvironment
import org.junit.Assert
import org.junit.Test
import java.util.*

class MissingFieldResolverTest {

    @Test(expected = FieldResolverError::class)
    fun `should throw error`() {
        SchemaParser.newParser()
                .schemaString("""
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
    fun `should call missing field resolver handler if provided`() {
        val schema = SchemaParser.newParser()
                .schemaString("""
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
                        .missingFieldResolverHandler(TestMissingFieldResolverHandler())
                        .build())
                .build()
                .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema).build()

        val result = gql
                .execute(ExecutionInput.newExecutionInput()
                        .query("""
                            query {
                                implementedField(input: "test-value")
                                missingField(input: 1)
                            }
                            """)
                        .context(Object())
                        .root(Object()))

        val expected = mapOf(
                "implementedField" to "Optional[test-value]",
                "missingField" to 1
        )

        Assert.assertEquals(expected, result.getData())
    }

    class TestMissingFieldResolverHandler: MissingFieldResolverHandler {
        override fun resolve(env: DataFetchingEnvironment?): Any? {
            return env?.getArgument("input");
        }
    }
}
