package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.relay.Connection
import graphql.relay.SimpleListConnection
import graphql.schema.*
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment
import org.junit.Test

class DirectiveTest {
    @Test
    fun `should apply @uppercase directive on field`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @uppercase on FIELD_DEFINITION
                
                type Query {
                    users: UserConnection
                }
                
                type UserConnection {
                    edges: [UserEdge!]!
                }
                
                type UserEdge {
                    node: User!
                } 
                
                type User {
                    id: ID!
                    name: String @uppercase
                }
                """)
            .resolvers(UsersQueryResolver())
            .directive("uppercase", UppercaseDirective())
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()

        val result = gql.execute(
            """
            query {
                users {
                    edges {
                        node {
                            id
                            name
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
            )
        )

        assertEquals(result.getData(), expected)
    }

    @Test
    fun `should apply @uppercase directive on object`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @uppercase on OBJECT
                
                type Query {
                    user: User
                }
                
                type User @uppercase {
                    id: ID!
                    name: String
                }
                """)
            .resolvers(UsersQueryResolver())
            .directive("uppercase", UppercaseDirective())
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()

        val result = gql.execute(
            """
            query {
                user {
                    id
                    name
                }
            }
            """)

        val expected = mapOf(
            "user" to mapOf("id" to "1", "name" to "LUKE")
        )

        assertEquals(result.getData(), expected)
    }

    @Test
    fun `should apply multiple directives`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @double repeatable on FIELD_DEFINITION
                directive @uppercase on FIELD_DEFINITION
                
                type Query {
                    user: User
                }
                
                type User {
                    id: ID!
                    name: String @uppercase @double
                }
                """)
            .resolvers(UsersQueryResolver())
            .directive("double", DoubleDirective())
            .directive("uppercase", UppercaseDirective())
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()

        val result = gql.execute(
            """
            query {
                user {
                    id
                    name
                }
            }
            """)

        val expected = mapOf(
            "user" to mapOf("id" to "1", "name" to "LUKELUKE")
        )

        assertEquals(result.getData(), expected)
    }

    @Test
    fun `should apply repeated directive`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @double repeatable on FIELD_DEFINITION
                
                type Query {
                    user: User
                }
                
                type User {
                    id: ID!
                    name: String @double @double
                }
                """)
            .resolvers(UsersQueryResolver())
            .directive("double", DoubleDirective())
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()

        val result = gql.execute(
            """
            query {
                user {
                    id
                    name
                }
            }
            """
        )

        val expected = mapOf(
            "user" to mapOf("id" to "1", "name" to "LukeLukeLukeLuke")
        )

        assertEquals(result.getData(), expected)
    }

    @Test
    fun `should have access to applied directives through the data fetching environment`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @uppercase on OBJECT
                
                type Query {
                    name: String @uppercase
                }
                
                """
            )
            .resolvers(NameResolver())
            .directive("uppercase", UppercaseDirective())
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()

        val result = gql.execute(
            """
            query {
                name
            }
            """
        )

        val expected = mapOf("name" to "LUKE")

        assertEquals(result.getData(), expected)
    }

    internal class NameResolver : GraphQLQueryResolver {
        fun name(environment: DataFetchingEnvironment): String {
            assertNotNull(environment.fieldDefinition.getAppliedDirective("uppercase"))
            assertNotNull(environment.fieldDefinition.getDirective("uppercase"))
            return "luke"
        }
    }

    @Test
    fun `should compile schema with directive that has enum parameter`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
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
            .dictionary(AllowedState::class)
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

    private class UppercaseDirective : SchemaDirectiveWiring {
        override fun onObject(environment: SchemaDirectiveWiringEnvironment<GraphQLObjectType>): GraphQLObjectType {
            val objectType = environment.element

            objectType.fields.forEach { field ->
                val originalDataFetcher = environment.codeRegistry.getDataFetcher(objectType, field)
                val wrappedDataFetcher = DataFetcherFactories.wrapDataFetcher(originalDataFetcher) { _, value ->
                    when (value) {
                        is String -> value.uppercase()
                        else -> value
                    }
                }

                environment.codeRegistry.dataFetcher(objectType, field, wrappedDataFetcher)
            }

            return objectType
        }

        override fun onField(environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>): GraphQLFieldDefinition {
            val field = environment.element
            val parentType = FieldCoordinates.coordinates(environment.fieldsContainer, environment.fieldDefinition)

            val originalDataFetcher = environment.codeRegistry.getDataFetcher(parentType, field)
            val wrappedDataFetcher = DataFetcherFactories.wrapDataFetcher(originalDataFetcher) { _, value ->
                (value as? String)?.uppercase()
            }

            environment.fieldDataFetcher = wrappedDataFetcher

            return field
        }
    }

    private class DoubleDirective : SchemaDirectiveWiring {

        override fun onField(environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>): GraphQLFieldDefinition {
            val field = environment.element
            val parentType = FieldCoordinates.coordinates(environment.fieldsContainer, environment.fieldDefinition)

            val originalDataFetcher = environment.codeRegistry.getDataFetcher(parentType, field)
            val wrappedDataFetcher = DataFetcherFactories.wrapDataFetcher(originalDataFetcher) { _, value ->
                val string = value as? String
                string + string
            }

            environment.codeRegistry.dataFetcher(parentType, wrappedDataFetcher)

            return field
        }
    }

    private class UsersQueryResolver : GraphQLQueryResolver {
        fun users(env: DataFetchingEnvironment): Connection<User> {
            return SimpleListConnection(listOf(User(1L, "Luke"))).get(env)
        }

        fun user(): User = User(1L, "Luke")

        private data class User(
            val id: Long,
            val name: String
        )
    }
}
