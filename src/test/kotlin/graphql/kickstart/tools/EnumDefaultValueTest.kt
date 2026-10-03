package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.DataFetchingEnvironment
import org.junit.Test

class EnumDefaultValueTest {

    @Test
    fun `enum value is not passed down to graphql-java`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    test(input: MySortSpecifier): SortBy
                }
                input MySortSpecifier {
                    sortBy: SortBy = createdOn
                    value: Int = 10
                }
                enum SortBy {
                    createdOn
                    updatedOn
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun test(input: MySortSpecifier): SortBy? = input.sortBy
            })
            .build()
            .makeExecutableSchema()

        val ggl = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()

        val data = assertNoGraphQlErrors(ggl, mapOf("input" to mapOf("value" to 1))) {
            """
            query test(${'$'}input: MySortSpecifier) {
                test(input: ${'$'}input)
            }
            """
        }

        assertEquals(data["test"], "createdOn")
    }

    @Test
    fun `enum list argument default value is passed to resolvers as enums`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    test(sortBy: [SortBy!] = [createdOn, updatedOn]): [String!]!
                }
                enum SortBy {
                    createdOn
                    updatedOn
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun test(sortBy: List<SortBy>, env: DataFetchingEnvironment): List<String> =
                    env.getArgument<List<Any>>("sortBy")!!.map { "${it.javaClass.simpleName}:$it" }
            })
            .build()
            .makeExecutableSchema()

        val ggl = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(ggl) {
            """
            query {
                test
            }
            """
        }

        assertEquals(data["test"], listOf("SortBy:createdOn", "SortBy:updatedOn"))
    }

    class MySortSpecifier {
        var sortBy: SortBy? = null
        var value: Int? = null
    }

    enum class SortBy {
        createdOn,
        updatedOn
    }
}
