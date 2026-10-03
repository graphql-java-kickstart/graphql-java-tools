package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.relay.Connection
import graphql.relay.SimpleListConnection
import graphql.schema.*
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment
import graphql.schema.idl.SchemaPrinter
import org.junit.Assert.assertThrows
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

    @Test
    fun `should resolve built-in directives`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                input BookFilter @oneOf {
                    id: Int
                    name: String
                }

                type Book {
                    id: Int!
                    name: String! @deprecated
                }

                type Query {
                    book(filter: BookFilter!): Book
                }
                """)
            .resolvers(BookQueryResolver())
            .build()
            .makeExecutableSchema()

        val filter = schema.getType("BookFilter") as GraphQLInputObjectType
        assert(filter.isOneOf)
        assertNotNull(filter.getAppliedDirective("oneOf"))
        assert((schema.getType("Book") as GraphQLObjectType).getField("name").isDeprecated)
    }

    @Test
    fun `should fill in default values of directive arguments that weren't supplied`() {
        val emailDirective = EmailDirective()
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @email(message: String = "{path} must be a valid email") on FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION
                directive @owner(team: String = "books-team") on SCHEMA | ENUM_VALUE

                schema @owner {
                    query: Query
                }

                enum AllowedState {
                    ALLOWED @owner
                    DISALLOWED
                }

                input PersonInput {
                    email: String @email
                }

                type Query {
                    contactEmail: String @email
                    updatePersonEmail(email: String @email, backupEmail: String @email(message: "invalid backup email")): String
                    updatePerson(person: PersonInput, state: AllowedState): String
                }
                """)
            .resolvers(PersonQueryResolver())
            .directive("email", emailDirective)
            .build()
            .makeExecutableSchema()

        assertEquals(
            emailDirective.messages,
            mapOf(
                "contactEmail" to ("{path} must be a valid email" to "{path} must be a valid email"),
                "email" to ("{path} must be a valid email" to "{path} must be a valid email"),
                "backupEmail" to ("invalid backup email" to "invalid backup email")
            )
        )
        val inputField = (schema.getType("PersonInput") as GraphQLInputObjectType).getField("email")
        assertEquals(inputField.getAppliedDirective("email").getArgument("message")?.getValue<String>(), "{path} must be a valid email")
        assertEquals(schema.getSchemaAppliedDirective("owner").getArgument("team")?.getValue<String>(), "books-team")
        val enumValue = (schema.getType("AllowedState") as GraphQLEnumType).getValue("ALLOWED")!!
        assertEquals(enumValue.getAppliedDirective("owner").getArgument("team")?.getValue<String>(), "books-team")
    }

    @Test
    fun `should apply directives on the schema and its extensions`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @link(url: String!, for: LinkPurpose, import: [String]) repeatable on SCHEMA
                directive @contact(name: String!) on SCHEMA

                enum LinkPurpose {
                    SECURITY
                    EXECUTION
                }

                extend schema @link(url: "https://specs.apollo.dev/federation/v2.3", import: ["@key", "@shareable"])

                schema @contact(name: "books-team") {
                    query: Query
                }

                extend schema @link(url: "https://specs.apollo.dev/link/v1.0", for: SECURITY)

                type Query {
                    books: [Book!]
                }

                type Book {
                    id: Int!
                    name: String!
                }
                """)
            .resolvers(QueryResolver())
            .dictionary(LinkPurpose::class)
            .build()
            .makeExecutableSchema()

        assertEquals(schema.schemaAppliedDirectives.map { it.name }, listOf("contact", "link", "link"))
        assertEquals(
            schema.getSchemaAppliedDirectives("link").map { it.getArgument("url")?.getValue<String>() },
            listOf("https://specs.apollo.dev/federation/v2.3", "https://specs.apollo.dev/link/v1.0")
        )
        assertEquals(
            schema.getSchemaAppliedDirectives("link").first().getArgument("import")?.getValue<List<String>>(),
            listOf("@key", "@shareable")
        )
        assertEquals(schema.getSchemaAppliedDirectives("link").last().getArgument("for")?.getValue<LinkPurpose>(), LinkPurpose.SECURITY)

        val printed = SchemaPrinter(SchemaPrinter.Options.defaultOptions().includeSchemaDefinition(true)).print(schema)
        assert(printed.contains("""schema @contact(name : "books-team") @link(import : ["@key", "@shareable"], url : "https://specs.apollo.dev/federation/v2.3") @link(for : SECURITY, url : "https://specs.apollo.dev/link/v1.0"){""")) {
            printed
        }
    }

    @Test
    fun `should fail on undeclared schema directive`() {
        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    extend schema @link(url: "https://specs.apollo.dev/federation/v2.3")

                    type Query {
                        books: [Book!]
                    }

                    type Book {
                        id: Int!
                        name: String!
                    }
                    """)
                .resolvers(QueryResolver())
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Found applied directive link without corresponding directive definition.")
    }

    @Test
    fun `should allow undeclared directives when enabled`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                extend schema @link(url: "https://specs.apollo.dev/federation/v2.3", import: ["@key", "@shareable"])

                type Query {
                    books: [Book!] @shareable
                }

                type Book @key(fields: "id", resolvable: true) @custom(weight: 1.5, limit: 3, tag: null) {
                    id: Int!
                    name: String!
                }
                """)
            .resolvers(QueryResolver())
            .options(SchemaParserOptions.newOptions().allowUndeclaredDirectives(true).build())
            .build()
            .makeExecutableSchema()

        val link = schema.getSchemaAppliedDirective("link")
        assertEquals(link.getArgument("import")?.getValue<List<String>>(), listOf("@key", "@shareable"))
        assertNotNull(schema.queryType.getField("books").getAppliedDirective("shareable"))

        val book = schema.getObjectType("Book")!!
        val key = book.getAppliedDirective("key")
        assertEquals(key.getArgument("fields")?.getValue<String>(), "id")
        assertEquals(key.getArgument("resolvable")?.getValue<Boolean>(), true)
        val custom = book.getAppliedDirective("custom")
        assertEquals(custom.getArgument("weight")?.getValue<Double>(), 1.5)
        assertEquals(custom.getArgument("limit")?.getValue<Int>(), 3)
        assertNull(custom.getArgument("tag")?.getValue<String>())
        // graphql-java rejects legacy directives without a definition
        assert(book.directives.isEmpty())

        val printed = SchemaPrinter(SchemaPrinter.Options.defaultOptions().includeSchemaDefinition(true)).print(schema)
        assert(printed.contains("""type Book @custom(limit : 3, tag : null, weight : 1.5) @key(fields : "id", resolvable : true) {""")) {
            printed
        }
    }

    @Test
    fun `should fail on undeclared directive with an argument whose type can't be guessed`() {
        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        books: [Book!] @policy(purpose: SECURITY)
                    }

                    type Book {
                        id: Int!
                        name: String!
                    }
                    """)
                .resolvers(QueryResolver())
                .options(SchemaParserOptions.newOptions().allowUndeclaredDirectives(true).build())
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Can't guess the type of argument policy#purpose of undeclared directive policy, please declare the directive.")
    }

    private class BookQueryResolver : GraphQLQueryResolver {
        fun book(filter: BookFilter): Book? = null
    }

    private data class BookFilter(
        val id: Int?,
        val name: String?
    )

    private class PersonQueryResolver : GraphQLQueryResolver {
        fun contactEmail(): String? = null
        fun updatePersonEmail(email: String?, backupEmail: String?): String? = email
        fun updatePerson(person: PersonInput?, state: AllowedState?): String? = null
    }

    private data class PersonInput(
        val email: String?
    )

    private class QueryResolver : GraphQLQueryResolver {
        fun books(): List<Book> {
            return listOf(Book(42L, "Test Book"))
        }
    }

    private data class Book(
        val id: Long,
        val name: String
    )

    private enum class LinkPurpose {
        SECURITY,
        EXECUTION
    }

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

    private class EmailDirective : SchemaDirectiveWiring {
        val messages = mutableMapOf<String, Pair<String?, String?>>()

        override fun onField(environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>): GraphQLFieldDefinition {
            recordMessage(environment)
            return environment.element
        }

        override fun onArgument(environment: SchemaDirectiveWiringEnvironment<GraphQLArgument>): GraphQLArgument {
            recordMessage(environment)
            return environment.element
        }

        private fun recordMessage(environment: SchemaDirectiveWiringEnvironment<*>) {
            val appliedMessage = environment.appliedDirective.getArgument("message")?.getValue<String>()
            val legacyMessage = environment.directive.getArgument("message")?.let { GraphQLArgument.getArgumentValue<String>(it) }
            messages[environment.element.name] = appliedMessage to legacyMessage
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
