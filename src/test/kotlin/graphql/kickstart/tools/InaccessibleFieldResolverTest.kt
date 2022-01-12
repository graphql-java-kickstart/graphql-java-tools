package graphql.kickstart.tools

import graphql.ExceptionWhileDataFetching
import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLSchema
import org.junit.Ignore
import org.junit.Test
import java.util.*

/**
 * Reflective access to private fields in closed modules is not possible since Java 17.
 * When using objects from closed modules in the schema the field resolver scanner will try to access their fields but fail.
 * If no other resolver is provided that will result in an [IllegalAccessException]
 */
class InaccessibleFieldResolverTest {

    @Test
    @Ignore // TODO enable test after upgrading to 17
    fun `private field from closed module is not accessible`() {
        val schema: GraphQLSchema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    locale: Locale
                }
                
                type Locale {
                  country: String!
                  languageTag: String!
                }
                """)
            .resolvers(Query())
            .build()
            .makeExecutableSchema()
        val gql: GraphQL = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()

        val result = gql.execute(
            """
            query {
                locale {
                    country
                    languageTag
                }
            }
            """
        )

        assertEquals(result.errors.size, 1)
        val exceptionWhileDataFetching = result.errors[0] as ExceptionWhileDataFetching
        assert(exceptionWhileDataFetching.exception is IllegalAccessException)
    }

    @Test
    fun `private field from closed module is accessible through resolver`() {
        val schema: GraphQLSchema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    locale: Locale
                }
                
                type Locale {
                  country: String!
                  languageTag: String!
                }
                """)
            .resolvers(Query(), LocaleResolver())
            .build()
            .makeExecutableSchema()
        val gql: GraphQL = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                locale {
                    country
                    languageTag
                }
            }
            """
        }

        assertEquals(data["locale"], mapOf("country" to "US", "languageTag" to "en-US"))
    }

    class Query : GraphQLQueryResolver {
        fun locale(): Locale = Locale.US
    }

    class LocaleResolver : GraphQLResolver<Locale> {
        fun languageTag(locale: Locale): String = locale.toLanguageTag()
    }
}
