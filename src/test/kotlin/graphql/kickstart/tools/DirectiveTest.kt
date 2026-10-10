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
    fun `should chain element changes of named directive wirings`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @auth on FIELD_DEFINITION
                directive @log on FIELD_DEFINITION

                type Query {
                    "Name"
                    name: String @auth @log
                }
                """)
            .resolvers(NameResolver())
            .directive("auth", DescriptionDirective("auth"))
            .directive("log", DescriptionDirective("log"))
            .build()
            .makeExecutableSchema()

        assertEquals(schema.queryType.getField("name").description, "Name +auth +log")
    }

    @Test
    fun `should chain element changes of named and static directive wirings`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @auth on FIELD_DEFINITION

                type Query {
                    "Name"
                    name: String @auth
                }
                """)
            .resolvers(NameResolver())
            .directive("auth", DescriptionDirective("auth"))
            .directiveWiring(DescriptionDirective("static"))
            .build()
            .makeExecutableSchema()

        assertEquals(schema.queryType.getField("name").description, "Name +auth +static")
    }

    @Test
    fun `should fail when a directive wiring returns null`() {
        val error = assertThrows(IllegalStateException::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    directive @auth on FIELD_DEFINITION

                    type Query {
                        name: String @auth
                    }
                    """)
                .resolvers(NameResolver())
                .directive("auth", object : SchemaDirectiveWiring {
                    override fun onField(environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>): GraphQLFieldDefinition? = null
                })
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "The SchemaDirectiveWiring MUST return a non null return value for element 'name'")
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
                    updatePersonEmail(primaryEmail: String @email, backupEmail: String @email(message: "invalid backup email")): String
                    updatePerson(person: PersonInput, state: AllowedState): String
                }
                """)
            .resolvers(PersonQueryResolver())
            .directive("email", emailDirective)
            .build()
            .makeExecutableSchema()

        val expectedMessages = mapOf(
            "contactEmail" to "{path} must be a valid email",
            "primaryEmail" to "{path} must be a valid email",
            "backupEmail" to "invalid backup email"
        )
        assertEquals(emailDirective.appliedMessages, expectedMessages)
        assertEquals(emailDirective.legacyMessages, expectedMessages)
        val inputField = (schema.getType("PersonInput") as GraphQLInputObjectType).getField("email")
        assertEquals(inputField.getAppliedDirective("email").getArgument("message")?.getValue<String>(), "{path} must be a valid email")
        assertEquals(schema.getSchemaAppliedDirective("owner").getArgument("team")?.getValue<String>(), "books-team")
        val enumValue = (schema.getType("AllowedState") as GraphQLEnumType).getValue("ALLOWED")!!
        assertEquals(enumValue.getAppliedDirective("owner").getArgument("team")?.getValue<String>(), "books-team")
    }

    @Test
    fun `should expose the directives of input fields through both the legacy and the applied view`() {
        val rangeDirective = RangeDirective()
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @range(min: Float!, max: Float!) on ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION
                directive @tag(name: String!) on INPUT_OBJECT

                type Query {
                    withInput(input: InputObject): Float
                }

                input InputObject @tag(name: "limits") {
                    value: Float @range(min: 0.00, max: 10.00)
                    nolimit: Float
                    limit: Float @range(min: 11.00, max: 15.00)
                }
                """)
            .resolvers(WithInputQueryResolver())
            .directive("range", rangeDirective)
            .build()
            .makeExecutableSchema()

        val expectedRanges = mapOf("value" to listOf(0.0, 10.0), "limit" to listOf(11.0, 15.0))
        assertEquals(rangeDirective.appliedRanges, expectedRanges)
        assertEquals(rangeDirective.legacyRanges, expectedRanges)
        assertEquals(rangeDirective.elementRanges, expectedRanges)
        val inputObject = schema.getType("InputObject") as GraphQLInputObjectType
        val expectedDirectives = mapOf("value" to listOf("range"), "nolimit" to emptyList(), "limit" to listOf("range"))
        assertEquals(inputObject.fields.associate { field -> field.name to field.directives.map { it.name } }, expectedDirectives)
        assertEquals(inputObject.fields.associate { field -> field.name to field.appliedDirectives.map { it.name } }, expectedDirectives)
        assertNotNull(inputObject.getDirective("tag"))
    }

    @Test
    fun `should validate directives on nested input fields reached through list and non-null types`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @size(min: Int = 0, max: Int = 2147483647) on ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION
                directive @range(min: Float!, max: Float!) on ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION

                type Query {
                    changeUser(input: ChangeUserInput): Boolean
                    addBook(bookInput: BookInput!): Boolean
                    rate(score: Float @range(min: 0, max: 5)): Boolean
                }

                input ChangeUserInput {
                    name: NameInput
                    aliases: [NameInput!]
                    guardian: GuardianInput!
                }

                input GuardianInput {
                    name: NameInput!
                }

                input NameInput {
                    forename: String! @size(min: 3, max: 25)
                }

                input BookInput {
                    id: Int! @range(min: 4, max: 10)
                    name: String!
                }
                """)
            .resolvers(ValidatedQueryResolver())
            .directiveWiring(ConstraintValidationWiring())
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()

        val invalid = gql.execute(
            """
            query {
                changeUser(input: { name: { forename: "Al" }, aliases: [{ forename: "Alice" }, { forename: "Bo" }], guardian: { name: { forename: "Ed" } } })
                addBook(bookInput: { id: 11, name: "Dune" })
                rate(score: 6)
            }
            """)
        assertEquals(invalid.errors.map { it.message }.sorted(), listOf(
            "Exception while fetching data (/addBook) : bookInput.id must be between 4.0 and 10.0",
            "Exception while fetching data (/changeUser) : input.name.forename, input.aliases[1].forename, input.guardian.name.forename must have a size between 3 and 25",
            "Exception while fetching data (/rate) : score must be between 0.0 and 5.0"
        ))

        val valid = gql.execute(
            """
            query {
                changeUser(input: { name: { forename: "Alice" }, aliases: [{ forename: "Bob" }], guardian: { name: { forename: "Eddie" } } })
                addBook(bookInput: { id: 5, name: "Dune" })
                rate(score: 4)
            }
            """)
        assertEquals(valid.errors, emptyList())
        assertEquals(valid.getData(), mapOf("changeUser" to true, "addBook" to true, "rate" to true))
    }

    @Test
    fun `should build input objects used by directive arguments even when they have directives themselves`() {
        val wiredArgumentTypes = mutableMapOf<String, String>()
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @meta(info: MetaInput!, extras: [MetaInput!]) on FIELD_DEFINITION

                type Query {
                    name: String @meta(info: { note: "info" }, extras: [{ note: "extra" }])
                }

                input MetaInput {
                    note: String @deprecated
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun name(): String? = null
            })
            .dictionary("MetaInput", MetaInput::class)
            .directive("meta", object : SchemaDirectiveWiring {
                override fun onField(environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>): GraphQLFieldDefinition {
                    environment.appliedDirective.arguments.forEach {
                        wiredArgumentTypes[it.name] = GraphQLTypeUtil.unwrapAll(it.type).javaClass.simpleName
                    }
                    return environment.element
                }
            })
            .build()
            .makeExecutableSchema()

        assertEquals(wiredArgumentTypes, mapOf("info" to "GraphQLInputObjectType", "extras" to "GraphQLInputObjectType"))
        assert((schema.getType("MetaInput") as GraphQLInputObjectType).getField("note").isDeprecated)
    }

    @Test
    fun `should wire input objects used by directive arguments with the real definitions of the directives applied to them`() {
        val metaDirective = "directive @meta(info: ConfigInput!) on FIELD_DEFINITION"
        val optionDirective = "directive @option(value: OptionInput) on INPUT_OBJECT | INPUT_FIELD_DEFINITION"
        listOf(listOf(metaDirective, optionDirective), listOf(optionDirective, metaDirective)).forEach { directives ->
            val optionRecorder = OptionRecorder()
            SchemaParser.newParser()
                .schemaString(
                    """
                    ${directives.joinToString("\n")}

                    type Query {
                        name(config: ConfigInput): String @meta(info: { level: 1 })
                    }

                    input ConfigInput @option(value: { label: "type" }) {
                        level: Int @option(value: { label: "field" })
                    }

                    input OptionInput {
                        label: String
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun name(config: ConfigInput?): String? = null
                })
                .dictionary("OptionInput", OptionInput::class)
                .directive("option", optionRecorder)
                .build()
                .makeExecutableSchema()

            val expectedOptions = mapOf(
                "ConfigInput" to listOf("GraphQLInputObjectType", mapOf("label" to "type"), mapOf("label" to "type")),
                "level" to listOf("GraphQLInputObjectType", mapOf("label" to "field"), mapOf("label" to "field"))
            )
            assert(optionRecorder.wiredOptions == expectedOptions) { "${optionRecorder.wiredOptions} with directives declared as $directives" }
        }
    }

    @Test
    fun `should wire input objects only once when a directive is applied within its own argument types`() {
        val limitDirective = "directive @limit(bound: BoundInput!) on INPUT_FIELD_DEFINITION"
        val limitsDirective = "directive @limits(bounds: [BoundInput!]) on FIELD_DEFINITION"
        listOf(listOf(limitsDirective, limitDirective), listOf(limitDirective, limitsDirective)).forEach { directives ->
            val wiredFields = mutableListOf<String>()
            val schema = SchemaParser.newParser()
                .schemaString(
                    """
                    ${directives.joinToString("\n")}

                    type Query {
                        name: String @limits(bounds: [{ max: 1 }])
                    }

                    input BoundInput {
                        max: Int @limit(bound: { max: 10 })
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun name(): String? = null
                })
                .dictionary("BoundInput", BoundInput::class)
                .directive("limit", object : SchemaDirectiveWiring {
                    override fun onInputObjectField(environment: SchemaDirectiveWiringEnvironment<GraphQLInputObjectField>): GraphQLInputObjectField {
                        wiredFields.add(environment.element.name)
                        return environment.element
                    }
                })
                .build()
                .makeExecutableSchema()

            assert(wiredFields == listOf("max")) { "$wiredFields with directives declared as $directives" }
            val limit = (schema.getType("BoundInput") as GraphQLInputObjectType).getField("max").getAppliedDirective("limit")
            assertEquals(limit.getArgument("bound")?.getValue<Map<String, Int>>(), mapOf("max" to 10))
            assert(GraphQLTypeUtil.unwrapAll(schema.getDirective("limit")!!.getArgument("bound").type) is GraphQLInputObjectType)
        }
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
        fun updatePersonEmail(primaryEmail: String?, backupEmail: String?): String? = primaryEmail
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
        val appliedMessages = mutableMapOf<String, String?>()
        val legacyMessages = mutableMapOf<String, String?>()

        override fun onField(environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>): GraphQLFieldDefinition {
            recordMessage(environment)
            return environment.element
        }

        override fun onArgument(environment: SchemaDirectiveWiringEnvironment<GraphQLArgument>): GraphQLArgument {
            recordMessage(environment)
            return environment.element
        }

        private fun recordMessage(environment: SchemaDirectiveWiringEnvironment<*>) {
            val name = environment.element.name
            appliedMessages[name] = environment.appliedDirective.getArgument("message")?.getValue<String>()
            legacyMessages[name] = environment.directive.getArgument("message")?.let { GraphQLArgument.getArgumentValue<String>(it) }
        }
    }

    private class WithInputQueryResolver : GraphQLQueryResolver {
        fun withInput(input: InputObject?): Double? = input?.value
    }

    private data class InputObject(
        val value: Double?,
        val nolimit: Double?,
        val limit: Double?
    )

    private class RangeDirective : SchemaDirectiveWiring {
        val appliedRanges = mutableMapOf<String, List<Double?>>()
        val legacyRanges = mutableMapOf<String, List<Double?>>()
        val elementRanges = mutableMapOf<String, List<Double?>>()

        override fun onInputObjectField(environment: SchemaDirectiveWiringEnvironment<GraphQLInputObjectField>): GraphQLInputObjectField {
            val field = environment.element
            appliedRanges[field.name] = listOf("min", "max").map { environment.appliedDirective.getArgument(it)?.getValue<Double>() }
            legacyRanges[field.name] = legacyRange(environment.directive)
            elementRanges[field.name] = legacyRange(field.getDirective("range"))
            return field
        }

        private fun legacyRange(directive: GraphQLDirective?): List<Double?> =
            listOf("min", "max").map { name -> directive?.getArgument(name)?.let { GraphQLArgument.getArgumentValue<Double>(it) } }
    }

    private data class MetaInput(
        val note: String?
    )

    // not private, so that the arguments can be converted to them
    data class ConfigInput(
        val level: Int?
    )

    private data class OptionInput(
        val label: String?
    )

    private data class BoundInput(
        val max: Int?
    )

    /**
     * Records the type and the applied and legacy value of the `value` argument of the directive at wiring time.
     */
    private class OptionRecorder : SchemaDirectiveWiring {
        val wiredOptions = mutableMapOf<String, List<Any?>>()

        override fun onInputObjectType(environment: SchemaDirectiveWiringEnvironment<GraphQLInputObjectType>): GraphQLInputObjectType {
            wiredOptions[environment.element.name] = option(environment)
            return environment.element
        }

        override fun onInputObjectField(environment: SchemaDirectiveWiringEnvironment<GraphQLInputObjectField>): GraphQLInputObjectField {
            wiredOptions[environment.element.name] = option(environment)
            return environment.element
        }

        private fun option(environment: SchemaDirectiveWiringEnvironment<*>): List<Any?> {
            val applied = environment.appliedDirective.getArgument("value")!!
            return listOf(
                applied.type.javaClass.simpleName,
                applied.getValue<Any?>(),
                GraphQLArgument.getArgumentValue<Any?>(environment.directive.getArgument("value"))
            )
        }
    }

    private class ValidatedQueryResolver : GraphQLQueryResolver {
        fun changeUser(input: ChangeUserInput?): Boolean = true
        fun addBook(bookInput: BookInput): Boolean = true
        fun rate(score: Double?): Boolean = true
    }

    // not private, so that the arguments can be converted to them
    data class ChangeUserInput(
        val name: NameInput?,
        val aliases: List<NameInput>?,
        val guardian: GuardianInput
    )

    data class GuardianInput(
        val name: NameInput
    )

    data class NameInput(
        val forename: String
    )

    data class BookInput(
        val id: Int,
        val name: String
    )

    /**
     * Validates @size and @range on arguments and on input fields nested in them, deciding at wiring time which fields need
     * validating by walking the argument types like graphql-java-extended-validation's DirectivesAndTypeWalker does.
     */
    private class ConstraintValidationWiring : SchemaDirectiveWiring {
        override fun onField(environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>): GraphQLFieldDefinition {
            val field = environment.element
            if (field.arguments.none { hasConstraint(it) || hasNestedConstraint(it.type) }) {
                return field
            }

            val coordinates = FieldCoordinates.coordinates(environment.fieldsContainer, field)
            val originalDataFetcher = environment.codeRegistry.getDataFetcher(coordinates, field)
            environment.codeRegistry.dataFetcher(coordinates, DataFetcher { env ->
                val violations = env.fieldDefinition.arguments.flatMap { argument ->
                    violations(argument.name, argument, argument.type, env.getArgument(argument.name))
                }
                if (violations.isNotEmpty()) {
                    throw IllegalArgumentException(violations.groupBy({ it.second }, { it.first }).map { (message, paths) -> "${paths.joinToString()} $message" }.joinToString("; "))
                }
                originalDataFetcher.get(env)
            })
            return field
        }

        private fun hasConstraint(container: GraphQLDirectiveContainer) =
            container.getDirective("size") != null || container.getDirective("range") != null

        private fun hasNestedConstraint(type: GraphQLInputType): Boolean {
            val unwrapped = GraphQLTypeUtil.unwrapAll(type)
            return unwrapped is GraphQLInputObjectType && unwrapped.fields.any { hasConstraint(it) || hasNestedConstraint(it.type) }
        }

        private fun violations(path: String, container: GraphQLDirectiveContainer, type: GraphQLInputType, value: Any?): List<Pair<String, String>> {
            val unwrapped = GraphQLTypeUtil.unwrapNonNull(type)
            return when {
                value == null -> emptyList()
                unwrapped is GraphQLList -> (value as List<*>).flatMapIndexed { i, item -> violations("$path[$i]", container, unwrapped.wrappedType as GraphQLInputType, item) }
                unwrapped is GraphQLInputObjectType -> unwrapped.fields.flatMap { violations("$path.${it.name}", it, it.type, (value as Map<*, *>)[it.name]) }
                else -> listOfNotNull(sizeViolation(container.getDirective("size"), value), rangeViolation(container.getDirective("range"), value)).map { path to it }
            }
        }

        private fun sizeViolation(directive: GraphQLDirective?, value: Any): String? {
            val min = directive?.getArgument("min")?.let { GraphQLArgument.getArgumentValue<Int>(it) } ?: return null
            val max = GraphQLArgument.getArgumentValue<Int>(directive.getArgument("max"))!!
            return if ((value as String).length !in min..max) "must have a size between $min and $max" else null
        }

        private fun rangeViolation(directive: GraphQLDirective?, value: Any): String? {
            val min = directive?.getArgument("min")?.let { GraphQLArgument.getArgumentValue<Double>(it) } ?: return null
            val max = GraphQLArgument.getArgumentValue<Double>(directive.getArgument("max"))!!
            return if ((value as Number).toDouble() !in min..max) "must be between $min and $max" else null
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

    private class DescriptionDirective(private val name: String) : SchemaDirectiveWiring {
        override fun onField(environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>): GraphQLFieldDefinition {
            val field = environment.element
            return field.transform { it.description("${field.description} +$name") }
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
