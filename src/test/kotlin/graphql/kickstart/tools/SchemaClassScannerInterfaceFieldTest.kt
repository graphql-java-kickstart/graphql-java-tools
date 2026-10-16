package graphql.kickstart.tools

import graphql.GraphQL
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLUnionType
import org.junit.Assert.assertThrows
import org.junit.Test

class SchemaClassScannerInterfaceFieldTest {

    private val interfaceOnlyUnionSchema = """
        type Query {
            query: TestInterface
        }

        interface TestInterface {
            testUnion: TestUnion
        }

        type TestInterfaceImpl implements TestInterface {
            testUnion: TestUnion1
            c: String
        }

        type TestUnion1 {
            a: String
        }

        type TestUnion2 {
            b: String
        }

        union TestUnion = TestUnion1 | TestUnion2
        """

    @Test
    fun `scanner handles unions used only by interface fields`() {
        val schema = SchemaParser.newParser()
            .schemaString(interfaceOnlyUnionSchema)
            .resolvers(InterfaceOnlyUnion())
            .dictionary(InterfaceOnlyUnion.TestInterfaceImpl::class, InterfaceOnlyUnion.TestUnion2::class)
            .build()
            .makeExecutableSchema()

        val testUnion = schema.getType("TestUnion") as GraphQLUnionType
        assertEquals(testUnion.types.map { it.name }.toSet(), setOf("TestUnion1", "TestUnion2"))

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                query {
                    testUnion {
                        ... on TestUnion1 { a }
                    }
                }
            }
            """
        }

        assertEquals(data["query"], mapOf("testUnion" to mapOf("a" to "a")))
    }

    @Test
    fun `scanner requires classes for all members of unions used only by interface fields`() {
        val error = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(interfaceOnlyUnionSchema)
                .resolvers(InterfaceOnlyUnion())
                .dictionary(InterfaceOnlyUnion.TestInterfaceImpl::class)
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Object type 'TestUnion2' is a member of a known union, but no class could be found for that type name.  Please pass a class for type 'TestUnion2' in the parser's dictionary.")
    }

    class InterfaceOnlyUnion : GraphQLQueryResolver {
        fun query(): TestInterface = TestInterfaceImpl(TestUnion1("a"), "c")

        interface TestInterface

        class TestInterfaceImpl(val testUnion: TestUnion1?, val c: String?) : TestInterface

        class TestUnion1(val a: String?)

        class TestUnion2(val b: String?)
    }

    @Test
    fun `scanner handles single member unions used only by interface fields`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    search: NodeSearchResponse
                }

                enum NodeSortField { TITLE }
                enum SortDirection { ASC DESC }

                union SortResponse = NodeSortResponse
                union Result = Node

                interface SearchResponse {
                    results: [Result!]!
                    order: SortResponse
                }

                type Node {
                    id: ID!
                    title: String
                }

                type NodeSortResponse {
                    field: NodeSortField
                    direction: SortDirection
                }

                type NodeSearchResponse implements SearchResponse {
                    results: [Node!]!
                    order: NodeSortResponse
                }
                """)
            .resolvers(SingleMemberUnions())
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Result") as GraphQLUnionType).types.map { it.name }, listOf("Node"))
        assertEquals((schema.getType("SortResponse") as GraphQLUnionType).types.map { it.name }, listOf("NodeSortResponse"))
    }

    class SingleMemberUnions : GraphQLQueryResolver {
        fun search(): NodeSearchResponse? = null

        enum class NodeSortField { TITLE }

        enum class SortDirection { ASC, DESC }

        class Node(val id: String, val title: String?)

        class NodeSortResponse(val field: NodeSortField?, val direction: SortDirection?)

        class NodeSearchResponse(val results: List<Node>, val order: NodeSortResponse?)
    }

    @Test
    fun `scanner handles unions used only by fields of interfaces discovered later in the scan`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    container: Container
                }

                interface Container {
                    entry: Entry
                }

                interface Entry {
                    content: Content
                }

                type Box implements Container {
                    entry: Book
                }

                type Book implements Entry {
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
            .resolvers(ChainedInterfaceUnion())
            .dictionary(ChainedInterfaceUnion.Box::class, ChainedInterfaceUnion.Image::class)
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Content") as GraphQLUnionType).types.map { it.name }.toSet(), setOf("Text", "Image"))
    }

    class ChainedInterfaceUnion : GraphQLQueryResolver {
        fun container(): Container? = null

        interface Container

        class Box(val entry: Book?) : Container

        class Book(val content: Text?)

        class Text(val text: String?)

        class Image(val url: String?)
    }

    @Test
    fun `scanner handles unions used only by interface fields that are found through another such union`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    item: Item
                }

                interface Item {
                    content: Content
                }

                type Book implements Item {
                    content: Text
                }

                type Text {
                    text: String
                }

                type Image implements Captioned {
                    url: String
                    caption: PlainCaption
                }

                union Content = Text | Image

                interface Captioned {
                    caption: Caption
                }

                type PlainCaption {
                    value: String
                }

                type RichCaption {
                    html: String
                }

                union Caption = PlainCaption | RichCaption
                """)
            .resolvers(NestedInterfaceOnlyUnion())
            .dictionary(NestedInterfaceOnlyUnion.Book::class, NestedInterfaceOnlyUnion.Image::class, NestedInterfaceOnlyUnion.RichCaption::class)
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Caption") as GraphQLUnionType).types.map { it.name }.toSet(), setOf("PlainCaption", "RichCaption"))
    }

    class NestedInterfaceOnlyUnion : GraphQLQueryResolver {
        fun item(): Item? = null

        interface Item

        class Book(val content: Text?) : Item

        class Text(val text: String?)

        class Image(val url: String?, val caption: PlainCaption?)

        class PlainCaption(val value: String?)

        class RichCaption(val html: String?)
    }

    @Test
    fun `scanner handles interfaces used only by interface fields`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    container: Container
                }

                interface Container {
                    entry: Entry
                }

                interface Entry {
                    id: ID!
                }

                interface Draft implements Entry {
                    id: ID!
                }

                type Box implements Container {
                    entry: Draft
                }
                """)
            .resolvers(InterfaceOnlyInterface())
            .dictionary(InterfaceOnlyInterface.Box::class)
            .build()
            .makeExecutableSchema()

        assert(schema.getType("Entry") is GraphQLInterfaceType)
    }

    class InterfaceOnlyInterface : GraphQLQueryResolver {
        fun container(): Container? = null

        interface Container

        interface Draft

        class Box(val entry: Draft?) : Container
    }

    @Test
    fun `scanner finds members of unions used by interface fields through dictionary types first`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    item: Item
                    other: OtherUnion
                }

                interface Item {
                    content: Content
                }

                type Book implements Item {
                    content: Text
                }

                type Text {
                    text: String
                }

                type Image {
                    url: String
                }

                union Content = Text | Image

                union OtherUnion = Holder

                type Holder {
                    image: Image
                    content: Content
                }
                """)
            .resolvers(DictionaryReachableUnion())
            .dictionary(DictionaryReachableUnion.Book::class, DictionaryReachableUnion.Holder::class)
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Content") as GraphQLUnionType).types.map { it.name }.toSet(), setOf("Text", "Image"))
    }

    class DictionaryReachableUnion : GraphQLQueryResolver {
        fun item(): Item? = null
        fun other(): OtherUnion? = null

        interface Item

        interface Content

        interface OtherUnion

        class Book(val content: Text?) : Item

        class Text(val text: String?) : Content

        class Image(val url: String?) : Content

        class Holder(val image: Image?, val content: Content?) : OtherUnion
    }

    @Test
    fun `scanner finds implementors of interfaces used by interface fields through dictionary types first`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    wrapper: Wrapper
                    owner: Owner
                }

                union Wrapper = Shelf

                type Shelf {
                    item: Item
                    book: Book
                    magazine: Magazine
                }

                interface Item {
                    title: String
                }

                type Book implements Item {
                    title: String
                }

                type Magazine implements Item {
                    title: String
                }

                interface Owner {
                    item: Item
                }
                """)
            .resolvers(DictionaryReachableInterface())
            .dictionary(DictionaryReachableInterface.Shelf::class)
            .build()
            .makeExecutableSchema()

        assertEquals(schema.getImplementations(schema.getType("Item") as GraphQLInterfaceType)!!.map { it.name }.toSet(), setOf("Book", "Magazine"))
    }

    class DictionaryReachableInterface : GraphQLQueryResolver {
        fun wrapper(): Wrapper? = null
        fun owner(): Owner? = null

        interface Wrapper

        interface Owner

        interface Item

        class Shelf(val item: Item?, val book: Book?, val magazine: Magazine?) : Wrapper

        class Book(val title: String?) : Item

        class Magazine(val title: String?) : Item
    }

    @Test
    fun `scanner finds members of unions used by interface fields through unused types when option is true`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    item: Item
                }

                interface Item {
                    content: Content
                }

                type Book implements Item {
                    content: Text
                }

                type Text {
                    text: String
                }

                type Image {
                    url: String
                    caption: Caption
                }

                type Caption {
                    value: String
                }

                union Content = Text | Image | Caption
                """)
            .resolvers(UnusedTypesReachableUnion())
            .dictionary(UnusedTypesReachableUnion.Book::class, UnusedTypesReachableUnion.Image::class)
            .options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build())
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Content") as GraphQLUnionType).types.map { it.name }.toSet(), setOf("Text", "Image", "Caption"))
    }

    class UnusedTypesReachableUnion : GraphQLQueryResolver {
        fun item(): Item? = null

        interface Item

        interface Content

        class Book(val content: Text?) : Item

        class Text(val text: String?) : Content

        class Image(val url: String?, val caption: Caption?) : Content

        class Caption(val value: String?) : Content
    }
}
