package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import org.junit.Before
import org.junit.Test

class ParameterizedGetterTest {

    private lateinit var gql: GraphQL

    @Before
    fun setup() {
        val schema = SchemaParser.newParser().schemaString("""
            type Query {
                human: Human
            }
            
            type Human {
                bestFriends: [Character!]!
                allFriends(limit: Int!): [Character!]!
            }
            
            type Character {
                name: String!
            }
        """.trimIndent())
                .resolvers(QueryResolver(), HumanResolver())
                .build()
                .makeExecutableSchema()
        gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(AsyncExecutionStrategy())
                .build()
    }

    @Test
    fun `parameterized query is resolved on data type instead of on its resolver`() {
        val data = assertNoGraphQlErrors(gql, mapOf("limit" to 10)) {
            """
                query allFriends(${'$'}limit: Int!) {
                    human {
                        allFriends(limit: ${'$'}limit) {
                            name
                        }
                    }
                }
            """
        }

        assert(data["human"] != null)
    }

    class QueryResolver : GraphQLQueryResolver {
        fun human(): Human = Human()
    }

    class Human {
        fun allFriends(limit: Int): List<Character> = listOf()
    }

    class HumanResolver : GraphQLResolver<Human> {
        fun bestFriends(human: Human): List<Character> = listOf()
    }

    class Character {
        val name: String? = null
    }
}
