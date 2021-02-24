package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLSchema
import org.junit.Test

class MultiResolverTest {

    private val schema: GraphQLSchema = SchemaParser.newParser()
        .schemaString(
            """
            type Query {
                person: Person
            }
            
            type Person {
                name: String!
                friends(friendName: String!): [Friend!]!
            }
            
            type Friend {
                name: String!
            }
            """)
        .resolvers(QueryWithPersonResolver(), PersonFriendResolver(), PersonNameResolver())
        .build()
        .makeExecutableSchema()
    private val gql: GraphQL = GraphQL.newGraphQL(schema)
        .queryExecutionStrategy(AsyncExecutionStrategy())
        .build()

    @Test
    fun `multiple resolvers for one data class should resolve methods with arguments`() {
        val data = assertNoGraphQlErrors(gql, mapOf("friendName" to "name")) {
            """
            query friendOfPerson(${'$'}friendName: String!) {
                person {
                    friends(friendName: ${'$'}friendName) {
                        name
                    }
                }
            }
            """
        }

        assertNotNull(data["person"])
    }

    class QueryWithPersonResolver : GraphQLQueryResolver {
        fun getPerson(): Person = Person()
    }

    class Person

    class Friend {
        var name: String? = null
    }

    class PersonFriendResolver : GraphQLResolver<Person> {
        fun friends(person: Person, friendName: String): List<Friend> = listOf()
    }

    class PersonNameResolver : GraphQLResolver<Person> {
        fun name(person: Person): String = "name"
    }
}
