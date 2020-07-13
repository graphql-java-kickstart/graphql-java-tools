package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.relay.Connection
import graphql.relay.SimpleListConnection
import graphql.schema.DataFetcherFactories
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment
import groovy.lang.Closure
import org.junit.Assert
import org.junit.Test
import java.util.function.BiFunction

class RelayConnectionTest {

    @Test
    fun `should compile relay schema when not using @connection directive`() {
        val schema = SchemaParser.newParser().schemaString("""
              directive @uppercase on FIELD_DEFINITION
  
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
                name: String @uppercase
              }
      
              type PageInfo
      
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
            .directive("uppercase", UppercaseDirective())
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
                        mapOf("id" to "1", "name" to "LUKE")
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

        Utils.assertNoGraphQlErrors(gql, emptyMap(), object : Closure<String>(null) {
            override fun call(): String {
                return """
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
        })
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

    private class UppercaseDirective : SchemaDirectiveWiring {

        override fun onField(environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>): GraphQLFieldDefinition {
            val field = environment.element
            val parentType = environment.fieldsContainer

            val originalDataFetcher = environment.codeRegistry.getDataFetcher(parentType, field)
            val wrappedDataFetcher = DataFetcherFactories.wrapDataFetcher(originalDataFetcher, BiFunction { env, value ->
                (value as? String)?.toUpperCase()
            })

            environment.codeRegistry.dataFetcher(parentType, field, wrappedDataFetcher)

            return field
        }
    }
}
