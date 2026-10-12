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
import kotlin.reflect.KClass

class TypeExtensionTest {

    @Test
    fun `union extensions add members to the union`() {
        val schema = buildUnionSchema(
            """
            union FooBar = Foo | Bar

            extend union FooBar = Baz
            """)

        val fooBar = schema.getType("FooBar") as GraphQLUnionType
        assertEquals(fooBar.types.map { it.name }, listOf("Foo", "Bar", "Baz"))
        assertEquals(definitionClass(fooBar), UnionTypeDefinition::class.java)
        assertEquals(fooBar.extensionDefinitions.size, 1)

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                fooBar {
                    __typename
                }
            }
            """
        }

        assertEquals(data["fooBar"], mapOf("__typename" to "Baz"))
    }

    @Test
    fun `union extensions declared before the union add members to it`() {
        val schema = buildUnionSchema(
            """
            extend union FooBar = Baz

            union FooBar = Foo | Bar
            """)

        assertEquals((schema.getType("FooBar") as GraphQLUnionType).types.map { it.name }, listOf("Foo", "Bar", "Baz"))
    }

    @Test
    fun `scanner requires classes for members of the union and its extensions`() {
        val baseMemberError = assertThrows(SchemaClassScannerError::class.java) {
            buildUnionSchema(
                """
                union FooBar = Foo | Bar

                extend union FooBar = Baz
                """,
                UnionQuery.Bar::class, UnionQuery.Baz::class)
        }

        assertEquals(baseMemberError.message, "Object type 'Foo' is a member of a known union, but no class could be found for that type name.  Please pass a class for type 'Foo' in the parser's dictionary.")

        val extensionMemberError = assertThrows(SchemaClassScannerError::class.java) {
            buildUnionSchema(
                """
                extend union FooBar = Baz

                union FooBar = Foo | Bar
                """,
                UnionQuery.Foo::class, UnionQuery.Bar::class)
        }

        assertEquals(extensionMemberError.message, "Object type 'Baz' is a member of a known union, but no class could be found for that type name.  Please pass a class for type 'Baz' in the parser's dictionary.")
    }

    private fun buildUnionSchema(union: String, vararg dictionary: KClass<*> = arrayOf(UnionQuery.Foo::class, UnionQuery.Bar::class, UnionQuery.Baz::class)) =
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    fooBar: FooBar
                }

                $union

                type Foo {
                    foo: String
                }

                type Bar {
                    bar: String
                }

                type Baz {
                    baz: String
                }
                """)
            .resolvers(UnionQuery())
            .dictionary(*dictionary)
            .build()
            .makeExecutableSchema()

    class UnionQuery : GraphQLQueryResolver {
        fun fooBar(): FooBar = Baz("baz")

        interface FooBar

        class Foo(val foo: String?) : FooBar

        class Bar(val bar: String?) : FooBar

        class Baz(val baz: String?) : FooBar
    }

    @Test
    fun `union extensions can add nested unions`() {
        // Audio is also returned by a field, since the scanner only expands nested unions it found some other way. That's the
        // same for the members of the union itself.
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    media: Media
                    audio: Audio
                }

                union Media = Book

                extend union Media = Audio

                union Audio = Song | Podcast

                type Book {
                    title: String
                }

                type Song {
                    title: String
                }

                type Podcast {
                    title: String
                }
                """)
            .resolvers(MediaQuery())
            .dictionary(MediaQuery.Book::class, MediaQuery.Song::class, MediaQuery.Podcast::class)
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Media") as GraphQLUnionType).types.map { it.name }, listOf("Book", "Song", "Podcast"))
    }

    class MediaQuery : GraphQLQueryResolver {
        fun media(): Media? = null
        fun audio(): Audio? = null

        interface Media

        interface Audio

        class Book(val title: String?) : Media

        class Song(val title: String?) : Audio

        class Podcast(val title: String?) : Audio
    }

    private val colorSchema = """
        type Query {
            color: Color
        }

        enum Color {
            RED
        }

        extend enum Color {
            GREEN
        }
        """

    @Test
    fun `enum extensions add values to the enum`() {
        val schema = SchemaParser.newParser()
            .schemaString(colorSchema)
            .resolvers(ColorQuery())
            .build()
            .makeExecutableSchema()

        val color = schema.getType("Color") as GraphQLEnumType
        assertEquals(color.values.map { it.name }, listOf("RED", "GREEN"))
        assertEquals(definitionClass(color), EnumTypeDefinition::class.java)
        assertEquals(color.extensionDefinitions.size, 1)

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                color
            }
            """
        }

        assertEquals(data["color"], "GREEN")
    }

    @Test
    fun `parser requires enum constants for the values of the enum and its extensions`() {
        val baseValueError = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(colorSchema)
                .resolvers(object : GraphQLQueryResolver {
                    fun color(): GreenOnly? = null
                })
                .build()
                .makeExecutableSchema()
        }

        assertEquals(baseValueError.message, "Expected value for name 'RED' in enum 'GreenOnly' but found none!")

        val extensionValueError = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(colorSchema)
                .resolvers(object : GraphQLQueryResolver {
                    fun color(): RedOnly? = null
                })
                .build()
                .makeExecutableSchema()
        }

        assertEquals(extensionValueError.message, "Expected value for name 'GREEN' in enum 'RedOnly' but found none!")
    }

    class ColorQuery : GraphQLQueryResolver {
        fun color(): Color = Color.GREEN

        enum class Color { RED, GREEN }
    }

    enum class GreenOnly { GREEN }

    enum class RedOnly { RED }

    @Test
    fun `interface extensions add fields and interfaces to the interface`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    node: Node
                }

                interface Entity {
                    id: ID!
                }

                interface Node {
                    id: ID!
                }

                extend interface Node implements Entity {
                    name: String
                }

                type Item implements Node & Entity {
                    id: ID!
                    name: String
                }
                """)
            .resolvers(NodeQuery())
            .dictionary(NodeQuery.Item::class)
            .build()
            .makeExecutableSchema()

        val node = schema.getType("Node") as GraphQLInterfaceType
        assertEquals(node.fieldDefinitions.map { it.name }, listOf("id", "name"))
        assertEquals(node.interfaces.map { it.name }, listOf("Entity"))
        assertEquals(definitionClass(node), InterfaceTypeDefinition::class.java)
        assertEquals(node.extensionDefinitions.size, 1)

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                node {
                    id
                    name
                }
            }
            """
        }

        assertEquals(data["node"], mapOf("id" to "1", "name" to "item"))
    }

    @Test
    fun `implementations need the fields of the interface and not only those of its extensions`() {
        val error = assertThrows(InvalidSchemaException::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        node: Node
                    }

                    interface Node {
                        id: ID!
                    }

                    extend interface Node {
                        name: String
                    }

                    type Item implements Node {
                        name: String
                    }
                    """)
                .resolvers(NodeQuery())
                .dictionary(NodeQuery.Item::class)
                .build()
                .makeExecutableSchema()
        }

        assert(error.message!!.contains("object type 'Item' does not implement interface 'Node' because field 'id' is missing")) { error.message!! }
    }

    class NodeQuery : GraphQLQueryResolver {
        fun node(): Node = Item("1", "item")

        interface Node

        class Item(val id: String, val name: String?) : Node
    }

    @Test
    fun `scanner handles unions used only by fields of interface extensions`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    item: Item
                }

                interface Item {
                    id: ID!
                }

                extend interface Item {
                    content: Content
                }

                type Book implements Item {
                    id: ID!
                    content: Text
                }

                type Text {
                    text: String
                }

                type Image {
                    url: String
                }

                union Content = Text | Image
                """)
            .resolvers(InterfaceExtensionUnion())
            .dictionary(InterfaceExtensionUnion.Book::class, InterfaceExtensionUnion.Image::class)
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Item") as GraphQLInterfaceType).fieldDefinitions.map { it.name }, listOf("id", "content"))
        assertEquals((schema.getType("Content") as GraphQLUnionType).types.map { it.name }.toSet(), setOf("Text", "Image"))
    }

    class InterfaceExtensionUnion : GraphQLQueryResolver {
        fun item(): Item? = null

        interface Item

        class Book(val id: String, val content: Text?) : Item

        class Text(val text: String?)

        class Image(val url: String?)
    }

    @Test
    fun `scalar extensions keep the description of the scalar`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @format(pattern: String!) on SCALAR

                type Query {
                    today: Date
                }

                "A date"
                scalar Date

                extend scalar Date @format(pattern: "yyyy-MM-dd")
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun today(): String = "2026-10-12"
            })
            .scalars(GraphQLScalarType.newScalar().name("Date").coercing(Scalars.GraphQLString.coercing).build())
            .build()
            .makeExecutableSchema()

        val date = schema.getType("Date") as GraphQLScalarType
        assertEquals(date.description, "A date")
        assertEquals(definitionClass(date), ScalarTypeDefinition::class.java)
        assertEquals(date.extensionDefinitions.size, 1)
    }

    @Test
    fun `object extensions apply their directives`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @key(fields: String!) repeatable on OBJECT | INTERFACE

                type Query {
                    product: Product
                }

                type Product @key(fields: "upc") {
                    upc: String
                }

                extend type Product @key(fields: "name") {
                    name: String
                }
                """)
            .resolvers(ProductQuery())
            .build()
            .makeExecutableSchema()

        val product = schema.getObjectType("Product")!!
        assertEquals(product.appliedDirectives.map { it.getArgument("fields")?.getValue<String>() }, listOf("upc", "name"))
        assertEquals(product.directives.map { it.name }, listOf("key", "key"))
        assertEquals(product.fieldDefinitions.map { it.name }, listOf("upc", "name"))
        assertEquals(definitionClass(product), ObjectTypeDefinition::class.java)
        assertEquals(product.extensionDefinitions.size, 1)

        val printed = SchemaPrinter().print(schema)
        assert(printed.contains("""type Product @key(fields : "upc") @key(fields : "name") {""")) { printed }
    }

    @Test
    fun `non-repeatable directives that an extension applies again keep the first one`() {
        val tagDirective = TagDirective()
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @tag(name: String!) on OBJECT

                type Query {
                    product: Product
                }

                type Product @tag(name: "catalog") {
                    upc: String
                }

                extend type Product @tag(name: "pricing")
                """)
            .resolvers(ProductQuery())
            .directive("tag", tagDirective)
            .build()
            .makeExecutableSchema()

        // graphql-java's SchemaGenerator rejects this schema
        val product = schema.getObjectType("Product")!!
        assertEquals(product.appliedDirectives.map { it.getArgument("name")?.getValue<String>() }, listOf("catalog"))
        assertEquals(product.directives.map { it.name }, listOf("tag"))
        assertEquals(tagDirective.tagged, listOf("Product=catalog"))

        val printed = SchemaPrinter().print(schema)
        assert(printed.contains("""type Product @tag(name : "catalog") {""")) { printed }
    }

    @Test
    fun `input object extensions apply their directives`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @a on INPUT_OBJECT
                directive @b on INPUT_OBJECT

                type Query {
                    search(filter: Filter): String
                }

                input Filter @a {
                    id: ID
                }

                extend input Filter @b
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun search(filter: TaggedQuery.Filter?): String? = null
            })
            .build()
            .makeExecutableSchema()

        val filter = schema.getType("Filter") as GraphQLInputObjectType
        assertEquals(filter.appliedDirectives.map { it.name }, listOf("a", "b"))
        assertEquals(filter.directives.map { it.name }, listOf("a", "b"))
        assertEquals(filter.extensionDefinitions.size, 1)
    }

    @Test
    fun `undeclared directives on type extensions are rejected unless allowed`() {
        val schemaString = """
            type Query {
                product: Product
            }

            type Product {
                upc: String
            }

            extend type Product @key(fields: "upc")
            """

        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(schemaString)
                .resolvers(ProductQuery())
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Found applied directive key without corresponding directive definition.")

        val schema = SchemaParser.newParser()
            .schemaString(schemaString)
            .resolvers(ProductQuery())
            .options(SchemaParserOptions.newOptions().allowUndeclaredDirectives(true).build())
            .build()
            .makeExecutableSchema()

        assertEquals(schema.getObjectType("Product")!!.getAppliedDirective("key")?.getArgument("fields")?.getValue<String>(), "upc")
    }

    @Test
    fun `undeclared directives that an extension applies again are all kept`() {
        // e.g. federation's @key, which is repeatable
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    product: Product
                }

                type Product @key(fields: "upc") {
                    upc: String
                }

                extend type Product @key(fields: "name") {
                    name: String
                }
                """)
            .resolvers(ProductQuery())
            .options(SchemaParserOptions.newOptions().allowUndeclaredDirectives(true).build())
            .build()
            .makeExecutableSchema()

        assertEquals(schema.getObjectType("Product")!!.appliedDirectives.map { it.getArgument("fields")?.getValue<String>() }, listOf("upc", "name"))
    }

    class ProductQuery : GraphQLQueryResolver {
        fun product(): Product? = null

        class Product(val upc: String?, val name: String?)
    }

    @Test
    fun `directives on extensions of every kind of type are wired`() {
        val tagDirective = TagDirective()
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @tag(name: String!) on OBJECT | INTERFACE | UNION | ENUM | INPUT_OBJECT

                type Query {
                    node(filter: Filter): Node
                    result: Result
                    color: Color
                }

                interface Node {
                    id: ID!
                }

                extend interface Node @tag(name: "node")

                type Item implements Node {
                    id: ID!
                }

                extend type Item @tag(name: "item")

                union Result = Item

                extend union Result @tag(name: "result")

                enum Color {
                    RED
                }

                extend enum Color @tag(name: "color")

                input Filter {
                    id: ID
                }

                extend input Filter @tag(name: "filter")
                """)
            .resolvers(TaggedQuery())
            .dictionary(TaggedQuery.Item::class)
            .directive("tag", tagDirective)
            .build()
            .makeExecutableSchema()

        listOf("Node", "Item", "Result", "Color", "Filter").forEach { name ->
            assertEquals((schema.getType(name) as GraphQLDirectiveContainer).appliedDirectives.map { it.name }, listOf("tag"))
        }
        assertEquals(tagDirective.tagged.toSet(), setOf("Node=node", "Item=item", "Result=result", "Color=color", "Filter=filter"))
    }

    class TaggedQuery : GraphQLQueryResolver {
        fun node(filter: Filter?): Node? = null
        fun result(): Result? = null
        fun color(): Color? = null

        interface Node

        interface Result

        enum class Color { RED }

        class Filter(val id: String?)

        class Item(val id: String) : Node, Result
    }

    private class TagDirective : SchemaDirectiveWiring {
        val tagged = mutableListOf<String>()

        override fun onObject(environment: SchemaDirectiveWiringEnvironment<GraphQLObjectType>) = tag(environment)

        override fun onInterface(environment: SchemaDirectiveWiringEnvironment<GraphQLInterfaceType>) = tag(environment)

        override fun onUnion(environment: SchemaDirectiveWiringEnvironment<GraphQLUnionType>) = tag(environment)

        override fun onEnum(environment: SchemaDirectiveWiringEnvironment<GraphQLEnumType>) = tag(environment)

        override fun onInputObjectType(environment: SchemaDirectiveWiringEnvironment<GraphQLInputObjectType>) = tag(environment)

        private fun <T : GraphQLDirectiveContainer> tag(environment: SchemaDirectiveWiringEnvironment<T>): T {
            tagged.add("${environment.element.name}=${environment.appliedDirective.getArgument("name")?.getValue<String>()}")
            return environment.element
        }
    }

    @Test
    fun `object extensions add interfaces to the object`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    item: Item
                }

                interface Node {
                    id: ID!
                }

                interface Named {
                    name: String
                }

                type Item implements Node {
                    id: ID!
                    name: String
                }

                extend type Item implements Named
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun item(): NodeQuery.Item? = null
            })
            .build()
            .makeExecutableSchema()

        assertEquals(schema.getObjectType("Item")!!.interfaces.map { it.name }, listOf("Node", "Named"))
        assertEquals(schema.getImplementations(schema.getType("Named") as GraphQLInterfaceType)!!.map { it.name }, listOf("Item"))
    }

    @Test
    fun `scanner requires classes for objects that implement an interface through an extension`() {
        val error = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        node: Node
                    }

                    interface Node {
                        id: ID!
                    }

                    type Item implements Node {
                        id: ID!
                    }

                    type Other {
                        id: ID!
                    }

                    extend type Other implements Node
                    """)
                .resolvers(NodeQuery())
                .dictionary(NodeQuery.Item::class)
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Object type 'Other' implements a known interface, but no class could be found for that type name.  Please pass a class for type 'Other' in the parser's dictionary.")
    }

    private val unusedUnionSchema = """
        type Query {
            name: String
        }

        union Unused = A | B

        extend union Unused = C

        type A {
            a: String
        }

        type B {
            b: String
        }

        type C {
            c: String
        }
        """

    @Test
    fun `unused unions get the members of their extensions when including unused types`() {
        val schema = SchemaParser.newParser()
            .schemaString(unusedUnionSchema)
            .resolvers(UnusedUnion())
            .dictionary(UnusedUnion.A::class, UnusedUnion.B::class, UnusedUnion.C::class)
            .options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build())
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Unused") as GraphQLUnionType).types.map { it.name }, listOf("A", "B", "C"))
    }

    @Test
    fun `unused definitions hold the definition of an extended type`() {
        val parser = SchemaParser.newParser()
            .schemaString(unusedUnionSchema)
            .resolvers(UnusedUnion())
            .build()

        assertEquals(parser.getUnusedDefinitions().map { it.javaClass.simpleName to it.name }.toSet(), setOf(
            "UnionTypeDefinition" to "Unused",
            "ObjectTypeDefinition" to "A",
            "ObjectTypeDefinition" to "B",
            "ObjectTypeDefinition" to "C"
        ))
    }

    class UnusedUnion : GraphQLQueryResolver {
        fun name(): String? = null

        class A(val a: String?)

        class B(val b: String?)

        class C(val c: String?)
    }

    @Test
    fun `values, fields and members repeated in an extension keep their first definition`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    color: Color
                    node: Node
                    fooBar: FooBar
                }

                enum Color {
                    "The base red"
                    RED
                }

                extend enum Color {
                    RED
                    GREEN
                }

                interface Node {
                    "The base id"
                    id: ID!
                }

                extend interface Node {
                    id: ID!
                    name: String
                }

                type Item implements Node {
                    id: ID!
                    name: String
                }

                union FooBar = Foo | Bar

                extend union FooBar = Foo | Bar | Baz

                type Foo {
                    foo: String
                }

                type Bar {
                    bar: String
                }

                type Baz {
                    baz: String
                }
                """)
            .resolvers(ColorQuery(), NodeQuery(), UnionQuery())
            .dictionary(NodeQuery.Item::class, UnionQuery.Foo::class, UnionQuery.Bar::class, UnionQuery.Baz::class)
            .build()
            .makeExecutableSchema()

        val color = schema.getType("Color") as GraphQLEnumType
        assertEquals(color.values.map { it.name }, listOf("RED", "GREEN"))
        assertEquals(color.getValue("RED")?.description, "The base red")

        val node = schema.getType("Node") as GraphQLInterfaceType
        assertEquals(node.fieldDefinitions.map { it.name }, listOf("id", "name"))
        assertEquals(node.getFieldDefinition("id").description, "The base id")

        assertEquals((schema.getType("FooBar") as GraphQLUnionType).types.map { it.name }, listOf("Foo", "Bar", "Baz"))
    }

    @Test
    fun `enums and unions defined only by extensions are built from all of them`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    color: Color
                    fooBar: FooBar
                }

                extend enum Color {
                    RED
                }

                extend enum Color {
                    GREEN
                }

                extend union FooBar = Foo | Bar

                extend union FooBar = Baz

                type Foo {
                    foo: String
                }

                type Bar {
                    bar: String
                }

                type Baz {
                    baz: String
                }
                """)
            .resolvers(ColorQuery(), UnionQuery())
            .dictionary(UnionQuery.Foo::class, UnionQuery.Bar::class, UnionQuery.Baz::class)
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Color") as GraphQLEnumType).values.map { it.name }, listOf("RED", "GREEN"))
        assertEquals((schema.getType("FooBar") as GraphQLUnionType).types.map { it.name }, listOf("Foo", "Bar", "Baz"))
    }

    @Test
    fun `extensions of a different kind than the type are rejected`() {
        val error = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        foo: Foo
                    }

                    type Foo {
                        foo: String
                    }

                    type Bar {
                        bar: String
                    }

                    extend union Foo = Bar
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun foo(): UnionQuery.Foo? = null
                })
                .build()
        }

        assertEquals(error.message, "Type 'Foo' is defined as an object type, but extended as a union.")
    }

    // through the interface, since graphql-java 25 and 26 disagree on which types have a nullable definition
    private fun definitionClass(type: GraphQLNamedSchemaElement) = type.definition?.javaClass
}
