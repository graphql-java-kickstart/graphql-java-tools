package graphql.kickstart.tools

import graphql.GraphQL
import graphql.Scalars
import graphql.language.EnumTypeDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.UnionTypeDefinition
import graphql.schema.*
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment
import graphql.schema.idl.SchemaPrinter
import graphql.schema.validation.InvalidSchemaException
import org.junit.Assert.assertThrows
import org.junit.Test

class TypeExtensionTest {

    @Test
    fun `extensions add to the types they extend`() {
        val schema = parser(
            """
            directive @format(pattern: String!) on SCALAR

            type Query {
                fooBar: FooBar
                color: Color
                node: Node
                today: Date
            }

            union FooBar = Foo | Bar
            extend union FooBar = Baz

            extend enum Color { GREEN }
            enum Color { RED }

            interface Entity { id: ID! }
            interface Node { id: ID! }
            extend interface Node implements Entity { name: String }

            type Item implements Node { id: ID! name: String }
            extend type Item implements Entity

            "A date"
            scalar Date
            extend scalar Date @format(pattern: "yyyy-MM-dd")

            $fooBarBaz
            """) {
            dictionary(Foo::class, Bar::class, Baz::class, Item::class)
            scalars(GraphQLScalarType.newScalar().name("Date").coercing(Scalars.GraphQLString.coercing).build())
        }.makeExecutableSchema()
            .assertEveryFieldResolves()

        val fooBar = schema.getType("FooBar") as GraphQLUnionType
        val color = schema.getType("Color") as GraphQLEnumType
        val node = schema.getType("Node") as GraphQLInterfaceType
        val item = schema.getType("Item") as GraphQLObjectType
        val date = schema.getType("Date") as GraphQLScalarType
        assertEquals(fooBar.types.map { it.name }, listOf("Foo", "Bar", "Baz"))
        assertEquals(color.values.map { it.name }, listOf("RED", "GREEN"))
        assertEquals(node.fieldDefinitions.map { it.name }, listOf("id", "name"))
        assertEquals(node.interfaces.map { it.name }, listOf("Entity"))
        assertEquals(item.interfaces.map { it.name }, listOf("Node", "Entity"))
        assertEquals(schema.getImplementations(schema.getType("Entity") as GraphQLInterfaceType)?.map { it.name }, listOf("Item"))
        assertEquals(date.description, "A date")
        assertEquals(date.appliedDirectives.map { it.name }, listOf("format"))

        val definitions = listOf(fooBar.definition, color.definition, node.definition, item.definition, date.definition)
        assertEquals(definitions.map { it?.javaClass }, listOf(
            UnionTypeDefinition::class.java,
            EnumTypeDefinition::class.java,
            InterfaceTypeDefinition::class.java,
            ObjectTypeDefinition::class.java,
            ScalarTypeDefinition::class.java
        ))
        val extensionDefinitions = listOf(fooBar.extensionDefinitions, color.extensionDefinitions, node.extensionDefinitions, item.extensionDefinitions, date.extensionDefinitions)
        assertEquals(extensionDefinitions.map { it.size }, listOf(1, 1, 1, 1, 1))

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                fooBar { __typename }
                color
                node { id name }
            }
            """
        }

        assertEquals(data, mapOf("fooBar" to mapOf("__typename" to "Baz"), "color" to "GREEN", "node" to mapOf("id" to "1", "name" to "item")))
    }

    @Test
    fun `union extensions can add nested unions that no field returns`() {
        val schema = parser(
            """
            type Query {
                fooBar: FooBar
            }

            union FooBar = Foo
            extend union FooBar = BarBaz
            union BarBaz = Bar | BazUnion
            union BazUnion = Baz

            $fooBarBaz
            """) {
            dictionary(Foo::class, Bar::class, Baz::class)
        }.makeExecutableSchema()
            .assertEveryFieldResolves()

        assertEquals((schema.getType("FooBar") as GraphQLUnionType).types.map { it.name }, listOf("Foo", "Bar", "Baz"))
        assertEquals((schema.getType("BarBaz") as GraphQLUnionType).types.map { it.name }, listOf("Bar", "Baz"))
    }

    @Test
    fun `scanner finds unions used only by fields of interface extensions`() {
        val schema = parser(
            """
            type Query {
                node: Node
            }

            interface Node { id: ID! }
            extend interface Node { fooBar: FooBar }

            type Item implements Node { id: ID! fooBar: Foo }

            union FooBar = Foo | Bar

            $fooBarBaz
            """) {
            dictionary(Item::class, Bar::class)
        }.makeExecutableSchema()
            .assertEveryFieldResolves()

        assertEquals((schema.getType("Node") as GraphQLInterfaceType).fieldDefinitions.map { it.name }, listOf("id", "fooBar"))
        assertEquals((schema.getType("FooBar") as GraphQLUnionType).types.map { it.name }.toSet(), setOf("Foo", "Bar"))
    }

    @Test
    fun `scanner requires classes for the members and implementations from the type and its extensions`() {
        val baseMemberError = assertThrows(SchemaClassScannerError::class.java) {
            parser(
                """
                type Query { fooBar: FooBar }
                union FooBar = Foo | Bar
                extend union FooBar = Baz
                $fooBarBaz
                """) {
                dictionary(Bar::class, Baz::class)
            }
        }

        assertEquals(baseMemberError.message, "Object type 'Foo' is a member of a known union, but no class could be found for that type name.  Please pass a class for type 'Foo' in the parser's dictionary.")

        val extensionMemberError = assertThrows(SchemaClassScannerError::class.java) {
            parser(
                """
                type Query { fooBar: FooBar }
                extend union FooBar = Baz
                union FooBar = Foo | Bar
                $fooBarBaz
                """) {
                dictionary(Foo::class, Bar::class)
            }
        }

        assertEquals(extensionMemberError.message, "Object type 'Baz' is a member of a known union, but no class could be found for that type name.  Please pass a class for type 'Baz' in the parser's dictionary.")

        val implementationError = assertThrows(SchemaClassScannerError::class.java) {
            parser(
                """
                type Query { node: Node }
                interface Node { id: ID! }
                type Item implements Node { id: ID! }
                type Other { id: ID! }
                extend type Other implements Node
                """) {
                dictionary(Item::class)
            }
        }

        assertEquals(implementationError.message, "Object type 'Other' implements a known interface, but no class could be found for that type name.  Please pass a class for type 'Other' in the parser's dictionary.")
    }

    @Test
    fun `parser requires the enum values and interface fields of the type and its extensions`() {
        listOf("enum Color { BLUE } extend enum Color { RED }", "enum Color { RED } extend enum Color { BLUE }").forEach { color ->
            val error = assertThrows(SchemaError::class.java) {
                parser("type Query { color: Color } $color").makeExecutableSchema()
            }

            assertEquals(error.message, "Expected value for name 'BLUE' in enum 'Color' but found none!")
        }

        val fieldError = assertThrows(InvalidSchemaException::class.java) {
            parser(
                """
                type Query { node: Node }
                interface Node { id: ID! }
                extend interface Node { name: String }
                type Item implements Node { name: String }
                """) {
                dictionary(Item::class)
            }.makeExecutableSchema()
        }

        val message = fieldError.message.orEmpty()
        assert(message.contains("object type 'Item' does not implement interface 'Node' because field 'id' is missing")) { message }
    }

    @Test
    fun `directives of extensions follow those of the type and are wired`() {
        val wiring = RecordingWiring()
        val (schema, warnings) = withWarnings {
            parser(
                """
                directive @tag(name: String!) repeatable on OBJECT | INTERFACE | UNION | ENUM | INPUT_OBJECT | SCALAR
                directive @once(name: String!) on OBJECT | INPUT_OBJECT

                type Query @once(name: "Query") {
                    node: Node
                    fooBar: FooBar
                    color: Color
                    search(filter: Filter): String
                    today: Date
                }

                extend type Query @once(name: "Query extension")

                interface Node @tag(name: "Node") { id: ID! }
                extend interface Node @tag(name: "Node extension")

                type Item implements Node @tag(name: "Item") { id: ID! }
                extend type Item @tag(name: "Item extension") { name: String }

                union FooBar @tag(name: "FooBar") = Baz
                extend union FooBar @tag(name: "FooBar extension")

                enum Color @tag(name: "Color") { RED GREEN }
                extend enum Color @tag(name: "Color extension")

                input Filter @tag(name: "Filter") { id: ID }
                extend input Filter @tag(name: "Filter extension") @once(name: "Filter extension")

                scalar Date @tag(name: "Date")
                extend scalar Date @tag(name: "Date extension")

                type Baz { baz: String }
                """) {
                dictionary(Item::class, Baz::class)
                scalars(GraphQLScalarType.newScalar().name("Date").coercing(Scalars.GraphQLString.coercing).build())
                directive("tag", wiring)
                directive("once", wiring)
            }.makeExecutableSchema()
        }
        schema.assertEveryFieldResolves()

        assertEquals(warnings, listOf("Directive @once isn't repeatable but an extension of type 'Query' applies it again, only the first one is kept."))

        val applied = listOf("Query", "Node", "Item", "FooBar", "Color", "Filter", "Date").associateWith { name ->
            (schema.getType(name) as GraphQLDirectiveContainer).appliedDirectives.map { it.describe() }
        }
        assertEquals(applied, mapOf(
            "Query" to listOf("@once(Query)"),
            "Node" to listOf("@tag(Node)", "@tag(Node extension)"),
            "Item" to listOf("@tag(Item)", "@tag(Item extension)"),
            "FooBar" to listOf("@tag(FooBar)", "@tag(FooBar extension)"),
            "Color" to listOf("@tag(Color)", "@tag(Color extension)"),
            "Filter" to listOf("@tag(Filter)", "@tag(Filter extension)", "@once(Filter extension)"),
            "Date" to listOf("@tag(Date)", "@tag(Date extension)")
        ))
        assertEquals(wiring.wired.sorted(), applied.values.flatten().sorted())

        val item = schema.getType("Item") as GraphQLObjectType
        val filter = schema.getType("Filter") as GraphQLInputObjectType
        assertEquals(item.directives.map { it.name }, listOf("tag", "tag"))
        assertEquals(filter.directives.map { it.name }, listOf("tag", "tag", "once"))
        assertEquals(schema.queryType.directives.map { it.name }, listOf("once"))
        assertEquals((schema.getType("Date") as GraphQLScalarType).directives.map { it.name }, listOf("tag", "tag"))
        assertEquals(item.fieldDefinitions.map { it.name }, listOf("id", "name"))
        assertEquals(filter.extensionDefinitions.size, 1)

        val printed = SchemaPrinter().print(schema)
        assert(printed.contains("""type Item implements Node @tag(name : "Item") @tag(name : "Item extension") {""")) { printed }
        assert(printed.contains("""type Query @once(name : "Query") {""")) { printed }
    }

    @Test
    fun `undeclared directives on extensions need allowUndeclaredDirectives and are all kept`() {
        val error = assertThrows(SchemaError::class.java) {
            parser(
                """
                type Query { today: String }
                extend type Query @key(fields: "upc")
                """).makeExecutableSchema()
        }

        assertEquals(error.message, "Found applied directive key without corresponding directive definition.")

        val schema = parser(
            """
            type Query @key(fields: "upc") { today: String }
            extend type Query @key(fields: "name")
            """) {
            options(SchemaParserOptions.newOptions().allowUndeclaredDirectives(true).build())
        }.makeExecutableSchema()
            .assertEveryFieldResolves()

        assertEquals(schema.queryType.appliedDirectives.map { it.getArgument("fields")?.getValue<String>() }, listOf("upc", "name"))
    }

    @Test
    fun `unused unions are reported by their definition and get the members of their extensions when included`() {
        val unusedUnion = """
            type Query { today: String }
            union FooBar = Foo | Bar
            extend union FooBar = Baz
            $fooBarBaz
            """

        val (unused, warnings) = withWarnings { parser(unusedUnion).getUnusedDefinitions() }

        assertEquals(unused.map { it.javaClass.simpleName to it.name }.toSet(), setOf(
            "UnionTypeDefinition" to "FooBar",
            "ObjectTypeDefinition" to "Foo",
            "ObjectTypeDefinition" to "Bar",
            "ObjectTypeDefinition" to "Baz"
        ))
        assertEquals(warnings, listOf("FooBar", "Foo", "Bar", "Baz").map { "Schema type was defined but can never be accessed, and can be safely deleted: $it" })

        val schema = parser(unusedUnion) {
            dictionary(Foo::class, Bar::class, Baz::class)
            options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build())
        }.makeExecutableSchema()
            .assertEveryFieldResolves()

        assertEquals((schema.getType("FooBar") as GraphQLUnionType).types.map { it.name }, listOf("Foo", "Bar", "Baz"))
    }

    @Test
    fun `repeats and extensions without a type keep building`() {
        val (schema, warnings) = withWarnings {
            parser(
                """
                type Query {
                    color: Color
                    node: Node
                    fooBar: FooBar
                }

                extend enum Color {
                    "The first red"
                    RED
                }
                extend enum Color { RED GREEN }

                interface Node {
                    "The base id"
                    id: ID!
                }
                extend interface Node { id: ID! name: String }

                type Item implements Node { id: ID! name: String }

                extend union FooBar = Foo | Bar
                extend union FooBar = Bar | Baz

                $fooBarBaz
                """) {
                dictionary(Item::class, Foo::class, Bar::class, Baz::class)
            }.makeExecutableSchema()
        }
        schema.assertEveryFieldResolves()

        assertEquals(warnings, listOf(
            "Type 'Node' declares field 'id' more than once, only the first one is kept.",
            "Type 'FooBar' declares member 'Bar' more than once, only the first one is kept.",
            "Type 'Color' declares value 'RED' more than once, only the first one is kept."
        ))

        val color = schema.getType("Color") as GraphQLEnumType
        assertEquals(color.values.map { it.name }, listOf("RED", "GREEN"))
        assertEquals(color.getValue("RED")?.description, "The first red")

        val node = schema.getType("Node") as GraphQLInterfaceType
        assertEquals(node.fieldDefinitions.map { it.name }, listOf("id", "name"))
        assertEquals(node.getFieldDefinition("id").description, "The base id")

        assertEquals((schema.getType("FooBar") as GraphQLUnionType).types.map { it.name }, listOf("Foo", "Bar", "Baz"))
    }

    @Test
    fun `extensions of a different kind than the type are ignored`() {
        val (schema, warnings) = withWarnings {
            parser(
                """
                type Query { node: Node }

                interface Node { id: ID! }
                extend type Node { name: String }

                type Item implements Node { id: ID! }
                extend union Item = Foo

                type Foo { foo: String }
                """) {
                dictionary(Item::class)
            }.makeExecutableSchema()
        }
        schema.assertEveryFieldResolves()

        assertEquals(warnings, listOf(
            "Type 'Node' is defined as an interface, but extended as an object type. The extension is ignored.",
            "Type 'Item' is defined as an object type, but extended as a union. The extension is ignored.",
            "Schema type was defined but can never be accessed, and can be safely deleted: Foo"
        ))

        val node = schema.getType("Node") as GraphQLInterfaceType
        val item = schema.getType("Item") as GraphQLObjectType
        assertEquals(node.fieldDefinitions.map { it.name }, listOf("id"))
        assertEquals(item.interfaces.map { it.name }, listOf("Node"))
        assertEquals(node.extensionDefinitions.size + item.extensionDefinitions.size, 0)
    }

    @Test
    fun `type errors name the fields of interface extensions that use the type`() {
        val error = assertThrows(SchemaError::class.java) {
            parser(
                """
                |type Query {
                |    today: String
                |}
                |
                |interface Node {
                |    id: ID!
                |}
                |
                |extend interface Node {
                |    search(filter: Filter): String
                |}
                |
                |input Filter {
                |    id: ID
                |}
                """.trimMargin()) {
                options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build())
            }.makeExecutableSchema()
        }

        val message = error.message.orEmpty()
        assert(message.startsWith("Type 'Filter' used by argument 'filter' of field 'Node.search' at line 10, column 20 is defined")) { message }
    }

    @Test
    fun `enums used only by directives get the values of their extensions`() {
        val schema = parser(
            """
            directive @link(for: Purpose) repeatable on SCHEMA
            extend schema @link(for: EXECUTION)

            type Query { today: String }

            enum Purpose { SECURITY }
            extend enum Purpose { EXECUTION }
            """).makeExecutableSchema()
            .assertEveryFieldResolves()

        assertEquals((schema.getType("Purpose") as GraphQLEnumType).values.map { it.value }, listOf<Any>("SECURITY", "EXECUTION"))
        assertEquals(schema.getSchemaAppliedDirectives("link").map { it.getArgument("for")?.getValue<String>() }, listOf("EXECUTION"))
    }

    private val fooBarBaz = """
        type Foo { foo: String }
        type Bar { bar: String }
        type Baz { baz: String }
        """

    private fun parser(schema: String, configure: SchemaParserBuilder.() -> Unit = {}) =
        SchemaParser.newParser().schemaString(schema).resolvers(Query()).apply(configure).build()

    class Query : GraphQLQueryResolver {
        fun fooBar(): FooBar = Baz("baz")
        fun color(): Color = Color.GREEN
        fun node(): Node = Item("1", "item")
        fun search(filter: Filter?): String? = null
        fun today(): String = "2026-10-12"
    }

    interface FooBar

    class Foo(val foo: String?) : FooBar

    class Bar(val bar: String?) : FooBar

    class Baz(val baz: String?) : FooBar

    interface Node

    class Item(val id: String, val name: String?, val fooBar: Foo? = null) : Node

    class Filter(val id: String?)

    enum class Color { RED, GREEN }

    private class RecordingWiring : SchemaDirectiveWiring {
        val wired = mutableListOf<String>()

        override fun onObject(environment: SchemaDirectiveWiringEnvironment<GraphQLObjectType>) = record(environment)

        override fun onInterface(environment: SchemaDirectiveWiringEnvironment<GraphQLInterfaceType>) = record(environment)

        override fun onUnion(environment: SchemaDirectiveWiringEnvironment<GraphQLUnionType>) = record(environment)

        override fun onEnum(environment: SchemaDirectiveWiringEnvironment<GraphQLEnumType>) = record(environment)

        override fun onInputObjectType(environment: SchemaDirectiveWiringEnvironment<GraphQLInputObjectType>) = record(environment)

        override fun onScalar(environment: SchemaDirectiveWiringEnvironment<GraphQLScalarType>) = record(environment)

        private fun <T : GraphQLDirectiveContainer> record(environment: SchemaDirectiveWiringEnvironment<T>): T {
            wired.add(environment.appliedDirective.describe())
            return environment.element
        }
    }
}

private fun GraphQLAppliedDirective.describe() = "@$name(${getArgument("name")?.getValue<String>()})"
