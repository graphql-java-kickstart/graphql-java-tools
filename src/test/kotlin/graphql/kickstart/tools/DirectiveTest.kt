package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment
import org.junit.Ignore
import org.junit.Test

class DirectiveTest {

    @Test
    @Ignore("Ignore until enums work in directives")
    fun `should compile schema with directive that has enum parameter`() {
        val schema = SchemaParser.newParser().schemaString("""
              directive @allowed(state: [AllowedState!]) on FIELD_DEFINITION
              
              enum AllowedState {
                  ALLOWED
                  DISALLOWED
              }
              
              type Book {
                  id: Int!
                  name: String! @allowed(state: [ALLOWED])
              }
              
              type Query {
                  books: [Book!]
              }
            """)
            .resolvers(QueryResolver())
            .directive("allowed", AllowedDirective())
            .build()
            .makeExecutableSchema()

        GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()
    }

    private class QueryResolver : GraphQLQueryResolver {
        fun books(): List<Book> {
            return listOf(Book(42L, "Test Book"))
        }
    }

    private data class Book(
        val id: Long,
        val name: String
    )

    private enum class AllowedState {
        ALLOWED,
        DISALLOWED
    }

    private class AllowedDirective : SchemaDirectiveWiring {
        override fun onField(environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>): GraphQLFieldDefinition {
            val field = environment.element

            // TODO

            return field
        }
    }
}
