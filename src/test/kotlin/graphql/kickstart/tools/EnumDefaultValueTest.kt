package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
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

        assert(data["test"] == "createdOn")
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
