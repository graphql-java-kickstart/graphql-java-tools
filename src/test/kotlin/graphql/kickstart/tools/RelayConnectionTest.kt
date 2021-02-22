package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.relay.Connection
import graphql.relay.SimpleListConnection
import graphql.schema.DataFetchingEnvironment
import org.junit.Assert
import org.junit.Test

class RelayConnectionTest {

    @Test
    fun `should compile relay schema when not using @connection directive`() {
        val schema = SchemaParser.newParser().schemaString("""
              type Query {
                users(first: Int, after: String): UserConnection
                otherTypes: AnotherTypeConnection
              }
      
              type UserConnection {
                edges: [UserEdge!]!
                pageInfo: PageInfo!
              }
      
              type UserEdge {
                node: User!
              } 
              
              type User {
                id: ID!
                name: String
              }
      
              type PageInfo {
                hasNextPage: Boolean
              }
      
              type AnotherTypeConnection {
                edges: [AnotherTypeEdge!]!
              }
      
              type AnotherTypeEdge {
                node: AnotherType!
              }
      
              type AnotherType {
                echo: String
              }
            """)
            .resolvers(QueryResolver())
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()

        val result = gql.execute("""
          query {
            users {
              edges {
                node {
                  id
                  name
                }
              }
            }
            otherTypes {
              edges {
                node {
                  echo
                }
              }
            }
          }
        """)

        val expected = mapOf(
            "users" to mapOf(
                "edges" to listOf(
                    mapOf("node" to
                        mapOf("id" to "1", "name" to "Luke")
                    )
                )
            ),
            "otherTypes" to mapOf(
                "edges" to listOf(
                    mapOf("node" to
                        mapOf("echo" to "echo-o-o")
                    )
                )
            )
        )

        Assert.assertEquals(expected, result.getData<Map<String, List<*>>>())
    }

    @Test
    fun `should compile relay schema when using @connection directive`() {
        val schema = SchemaParser.newParser()
                .file("RelayConnection.graphqls")
                .resolvers(QueryResolver())
                .dictionary(User::class.java)
                .build()
                .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(AsyncExecutionStrategy())
                .build()

        assertNoGraphQlErrors(gql) {
            """
                  query {
                    users {
                      edges {
                        cursor
                        node {
                          id
                          name
                        }
                      }
                      pageInfo {
                        hasPreviousPage
                        hasNextPage
                        startCursor
                        endCursor
                      }
                    }
                  }
            """
        }
    }

    private class QueryResolver : GraphQLQueryResolver {
        fun users(first: Int?, after: String?, env: DataFetchingEnvironment): Connection<User> {
            return SimpleListConnection(listOf(User(1L, "Luke"))).get(env)
        }

        fun otherTypes(env: DataFetchingEnvironment): Connection<AnotherType> {
            return SimpleListConnection(listOf(AnotherType("echo-o-o"))).get(env)
        }
    }

    private data class User(
        val id: Long,
        val name: String
    )

    private data class AnotherType(
        val echo: String
    )
}
