package graphql.kickstart.tools

import graphql.relay.Connection
import graphql.relay.SimpleListConnection
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import org.junit.Test

class DeprecatedDirectiveTest {
    @Test
    fun `should apply @deprecated directive on output field with default reason`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
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
                    name: String @deprecated
                }
                """)
            .resolvers(UsersQueryResolver())
            .build()
            .makeExecutableSchema()

        val userType = schema.getType("User") as GraphQLObjectType
        val nameDefinition = userType.getField("name")

        assert(nameDefinition.isDeprecated)
        assertEquals(nameDefinition.deprecationReason, "No longer supported")
    }

    @Test
    fun `should apply @deprecated directive on output field with custom reason`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
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
                    name: String @deprecated(reason: "Use firstName and lastName instead")
                }
                """)
            .resolvers(UsersQueryResolver())
            .build()
            .makeExecutableSchema()

        val userType = schema.getType("User") as GraphQLObjectType
        val nameDefinition = userType.getField("name")

        assert(nameDefinition.isDeprecated)
        assertEquals(nameDefinition.deprecationReason, "Use firstName and lastName instead")
    }

    @Test
    fun `should apply @deprecated directive on enum value with default reason`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    users: UserConnection
                }
                
                type UserConnection {
                    edges: [UserEdge!]!
                }
                
                type UserEdge {
                    node: User!
                } 

                enum UserType {
                    JEDI
                    BASIC
                    DROID @deprecated
                }
                
                type User {
                    id: ID!
                    name: String
                    type: UserType
                }
                """)
            .resolvers(UsersQueryResolver())
            .build()
            .makeExecutableSchema()

        val userTypeEnum = schema.getType("UserType") as GraphQLEnumType
        val droidValue = userTypeEnum.getValue("DROID")

        assert(droidValue.isDeprecated)
        assertEquals(droidValue.deprecationReason, "No longer supported")
    }

    @Test
    fun `should apply @deprecated directive on enum value with custom reason`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    users: UserConnection
                }
                
                type UserConnection {
                    edges: [UserEdge!]!
                }
                
                type UserEdge {
                    node: User!
                } 

                enum UserType {
                    JEDI
                    BASIC
                    DROID @deprecated(reason: "This value is no longer used")
                }
                
                type User {
                    id: ID!
                    name: String
                    type: UserType
                }
                """)
            .resolvers(UsersQueryResolver())
            .build()
            .makeExecutableSchema()

        val userTypeEnum = schema.getType("UserType") as GraphQLEnumType
        val droidValue = userTypeEnum.getValue("DROID")

        assert(droidValue.isDeprecated)
        assertEquals(droidValue.deprecationReason, "This value is no longer used")
    }

    @Test
    fun `should apply @deprecated directive on argument with default reason`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    users(first: Int @deprecated): UserConnection
                }
                
                type UserConnection {
                    edges: [UserEdge!]!
                }
                
                type UserEdge {
                    node: User!
                } 
                
                type User {
                    id: ID!
                    name: String
                }
                """)
            .resolvers(UsersQueryResolver())
            .build()
            .makeExecutableSchema()

        val usersQuery = schema.queryType.getField("users")
        val firstArgument = usersQuery.getArgument("first")

        assert(firstArgument.isDeprecated)
        assertEquals(firstArgument.deprecationReason, "No longer supported")
    }

    @Test
    fun `should apply @deprecated directive on argument with custom reason`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    users(first: Int @deprecated(reason: "Please do not use this argument")): UserConnection
                }
                
                type UserConnection {
                    edges: [UserEdge!]!
                }
                
                type UserEdge {
                    node: User!
                } 
                
                type User {
                    id: ID!
                    name: String
                }
                """)
            .resolvers(UsersQueryResolver())
            .build()
            .makeExecutableSchema()

        val usersQuery = schema.queryType.getField("users")
        val firstArgument = usersQuery.getArgument("first")

        assert(firstArgument.isDeprecated)
        assertEquals(firstArgument.deprecationReason, "Please do not use this argument")
    }

    @Test
    fun `should apply @deprecated directive on directive argument with default reason`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @uppercase(firstCharacterOnly: Boolean @deprecated) on FIELD_DEFINITION

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
                    name: String
                }
                """)
            .resolvers(UsersQueryResolver())
            .build()
            .makeExecutableSchema()

        val directive = schema.getDirective("uppercase")
        val argument = directive.getArgument("firstCharacterOnly")

        assert(argument.isDeprecated)
        assertEquals(argument.deprecationReason, "No longer supported")
    }

    @Test
    fun `should apply @deprecated directive on directive argument with custom reason`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @uppercase(firstCharacterOnly: Boolean @deprecated(reason: "Do not use this thing")) on FIELD_DEFINITION

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
                    name: String
                }
                """)
            .resolvers(UsersQueryResolver())
            .build()
            .makeExecutableSchema()

        val directive = schema.getDirective("uppercase")
        val argument = directive.getArgument("firstCharacterOnly")

        assert(argument.isDeprecated)
        assertEquals(argument.deprecationReason, "Do not use this thing")
    }

    @Test
    fun `should apply @deprecated directive on input field with default reason`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    users(connectionInput: ConnectionInput): UserConnection
                }
                
                input ConnectionInput {
                    first: Int @deprecated
                }
                
                type UserConnection {
                    edges: [UserEdge!]!
                }
                
                type UserEdge {
                    node: User!
                } 
                
                type User {
                    id: ID!
                    name: String
                }
                """)
            .resolvers(UsersQueryResolver())
            .build()
            .makeExecutableSchema()

        val connectionInputType = schema.getType("ConnectionInput") as GraphQLInputObjectType
        val firstField = connectionInputType.getField("first")

        assert(firstField.isDeprecated)
        assertEquals(firstField.deprecationReason, "No longer supported")
    }

    @Test
    fun `should apply @deprecated directive on input field with custom reason`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    users(connectionInput: ConnectionInput): UserConnection
                }
                
                input ConnectionInput {
                    first: Int @deprecated(reason: "Please do not use this field")
                }
                
                type UserConnection {
                    edges: [UserEdge!]!
                }
                
                type UserEdge {
                    node: User!
                } 
                
                type User {
                    id: ID!
                    name: String
                }
                """)
            .resolvers(UsersQueryResolver())
            .build()
            .makeExecutableSchema()

        val connectionInputType = schema.getType("ConnectionInput") as GraphQLInputObjectType
        val firstField = connectionInputType.getField("first")

        assert(firstField.isDeprecated)
        assertEquals(firstField.deprecationReason, "Please do not use this field")
    }

    private enum class UserType {
        JEDI,
        BASIC,
        DROID
    }

    private class UsersQueryResolver : GraphQLQueryResolver {
        fun users(env: DataFetchingEnvironment): Connection<User> {
            return SimpleListConnection(listOf(User(1L, "Luke Skywalker", "Luke", "Skywalker", UserType.JEDI))).get(env)
        }

        private data class User(
            val id: Long,
            val name: String,
            val firstName: String,
            val lastName: String,
            val type: UserType
        )
    }
}
