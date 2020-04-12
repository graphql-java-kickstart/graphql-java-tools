package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.relay.Connection
import graphql.relay.SimpleListConnection
import graphql.schema.DataFetchingEnvironment
import groovy.lang.Closure
import org.junit.Test

class RelayConnectionTest {

    @Test
    fun `should compile relay schema`() {
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

    class QueryResolver : GraphQLQueryResolver {
        fun users(first: Int?, after: String?, env: DataFetchingEnvironment): Connection<User> {
            return SimpleListConnection(listOf(User(1L, "Luke"))).get(env)
        }
    }

    class User(
        val id: Long,
        val name: String
    )
}
