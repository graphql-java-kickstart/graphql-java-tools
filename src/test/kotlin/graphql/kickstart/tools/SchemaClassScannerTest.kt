package graphql.kickstart.tools

import graphql.GraphQL
import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.Value
import graphql.schema.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.*
import java.util.concurrent.CompletableFuture

@OptIn(ExperimentalCoroutinesApi::class)
class SchemaClassScannerTest {

    @Test
    fun `scanner handles futures and immediate return types`() {
        SchemaParser.newParser()
            .resolvers(FutureImmediateQuery())
            .schemaString(
                """
                type Query {
                    future: Int!
                    immediate: Int!
                }
                """)
            .build()
    }

    private class FutureImmediateQuery : GraphQLQueryResolver {
        fun future(): CompletableFuture<Int> =
            CompletableFuture.completedFuture(1)

        fun immediate(): Int = 1
    }

    @Test
    fun `scanner handles primitive and boxed return types`() {
        SchemaParser.newParser()
            .resolvers(PrimitiveBoxedQuery())
            .schemaString(
                """
                type Query {
                    primitive: Int!
                    boxed: Int!
                }
                """)
            .build()
    }

    private class PrimitiveBoxedQuery : GraphQLQueryResolver {
        fun primitive(): Int = 1

        fun boxed(): Int? = null
    }

    @Test
    fun `scanner handles different scalars with same java class`() {
        SchemaParser.newParser()
            .resolvers(ScalarDuplicateQuery())
            .schemaString(
                """
                type Query {
                    string: String!
                    id: ID!
                }
                """)
            .build()
    }

    private class ScalarDuplicateQuery : GraphQLQueryResolver {
        fun string(): String = ""
        fun id(): String = ""
    }

    @Test
    fun `scanner handles interfaces referenced by objects that aren't explicitly used`() {
        val schema = SchemaParser.newParser()
            .resolvers(InterfaceMissingQuery())
            .schemaString(
                """
                interface Interface {
                    id: ID!
                }

                type Query implements Interface {
                    id: ID!
                }
                """)
            .build()
            .makeExecutableSchema()

        val interfaceType = schema.additionalTypes.find { it is GraphQLInterfaceType }
        assertNotNull(interfaceType)
    }

    private class InterfaceMissingQuery : GraphQLQueryResolver {
        fun id(): String = ""
    }

    @Test
    fun `scanner handles input types that reference other input types`() {
        val schema = SchemaParser.newParser()
            .resolvers(MultipleInputTypeQuery())
            .schemaString(
                """
                input FirstInput {
                    id: String!
                    second: SecondInput!
                    third: ThirdInput!
                }
                input SecondInput {
                    id: String!
                }
                input ThirdInput {
                    id: String!
                }

                type Query {
                    test(input: FirstInput): String!
                }
                """)
            .build()
            .makeExecutableSchema()

        val inputTypeCount = schema.additionalTypes.count { it is GraphQLInputType }
        assertEquals(inputTypeCount, 3)
    }

    private class MultipleInputTypeQuery : GraphQLQueryResolver {

        fun test(input: FirstInput): String = ""

        class FirstInput {
            var id: String? = null

            fun second(): SecondInput = SecondInput()
            var third: ThirdInput? = null
        }

        class SecondInput {
            var id: String? = null
        }

        class ThirdInput {
            var id: String? = null
        }
    }

    @Test
    fun `scanner ignores fluent setters when finding input field types`() {
        SchemaParser.newParser()
            .resolvers(FluentSetterMutation(), object : GraphQLQueryResolver {
                fun test(): Boolean = true
            })
            .schemaString(
                """
                type Query {
                    test: Boolean
                }

                type Mutation {
                    createRepairApply(body: RepairApplyInput): Boolean
                    createRepairMan(body: RepairManInput): Boolean
                }

                input RepairApplyInput {
                    id: ID
                    repairMan: RepairManInput
                    reviewer: RepairManInput
                    approver: RepairManInput
                }

                input RepairManInput {
                    id: ID
                    userName: String
                }
                """)
            .build()
            .makeExecutableSchema()
    }

    private class FluentSetterMutation : GraphQLMutationResolver {
        fun createRepairApply(body: RepairApply): Boolean = true
        fun createRepairMan(body: RepairMan): Boolean = true

        class RepairApply {
            var id: String? = null
            var repairMan: RepairMan? = null
            private var reviewer: RepairMan? = null
            @JvmField
            var approver: RepairMan? = null

            fun repairMan(repairMan: RepairMan?): RepairApply {
                this.repairMan = repairMan
                return this
            }

            fun reviewer(): RepairMan? = reviewer

            fun reviewer(reviewer: RepairMan?): RepairApply {
                this.reviewer = reviewer
                return this
            }

            fun approver(approver: RepairMan?): RepairApply {
                this.approver = approver
                return this
            }
        }

        class RepairMan {
            var id: String? = null
            var userName: String? = null
        }
    }

    @Test
    fun `scanner finds input field types through getters with arguments`() {
        SchemaParser.newParser()
            .resolvers(GetterWithArgumentsQuery())
            .schemaString(
                """
                type Query {
                    foo(input: FooInput): Foo
                }

                type Foo {
                    bar: Bar
                }

                type Bar {
                    name: String
                }

                input FooInput {
                    bar: BarInput
                }

                input BarInput {
                    name: String
                }
                """)
            .build()
            .makeExecutableSchema()
    }

    private class GetterWithArgumentsQuery : GraphQLQueryResolver {
        fun foo(input: Foo): Foo = input

        class Foo {
            private var bar: Bar? = null

            fun getBar(env: DataFetchingEnvironment): Bar? = bar

            fun setBar(bar: Bar?) {
                this.bar = bar
            }
        }

        class Bar {
            var name: String? = null
        }
    }

    @Test
    fun `scanner handles input types extensions`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query { test: Boolean }

                type Mutation {
                    save(input: UserInput!): Boolean
                }
                
                input UserInput {
                    name: String                        
                }
                
                extend input UserInput {
                    password: String
                }
                """)
            .resolvers(
                object : GraphQLMutationResolver {
                    fun save(map: Map<*, *>): Boolean = true
                },
                object : GraphQLQueryResolver {
                    fun test(): Boolean = true
                }
            )
            .build()
            .makeExecutableSchema()

        val inputTypeExtensionCount = schema.additionalTypes
            .filterIsInstance<GraphQLInputObjectType>()
            .flatMap { it.extensionDefinitions }
            .count()
        assertEquals(inputTypeExtensionCount, 1)
    }

    @Test
    fun `scanner allows multiple return types for custom scalars`() {
        val schema = SchemaParser.newParser()
            .resolvers(ScalarsWithMultipleTypes())
            .scalars(GraphQLScalarType.newScalar()
                .name("UUID")
                .description("Test scalars with duplicate types")
                .coercing(object : Coercing<Any, Any> {
                    override fun serialize(dataFetcherResult: Any, context: GraphQLContext, locale: Locale): Any? = null
                    override fun parseValue(input: Any, context: GraphQLContext, locale: Locale): Any = input
                    override fun parseLiteral(input: Value<*>, variables: CoercedVariables, context: GraphQLContext, locale: Locale): Any = input
                }).build())
            .schemaString(
                """
                scalar UUID

                type Query {
                    first: UUID
                    second: UUID
                }
                """)
            .build()
            .makeExecutableSchema()

        assert(schema.typeMap.containsKey("UUID"))
    }

    class ScalarsWithMultipleTypes : GraphQLQueryResolver {
        fun first(): Int? = null
        fun second(): String? = null
    }

    @Test
    fun `scanner handles multiple interfaces that are not used as field types`() {
        val schema = SchemaParser.newParser()
            .resolvers(MultipleInterfaces())
            .schemaString(
                """
                type Query {
                    query1: NamedResourceImpl
                    query2: VersionedResourceImpl
                }

                interface NamedResource {
                    name: String!
                }

                interface VersionedResource {
                    version: Int!
                }

                type NamedResourceImpl implements NamedResource {
                    name: String!
                }

                type VersionedResourceImpl implements VersionedResource {
                    version: Int!
                }
                """)
            .build()
            .makeExecutableSchema()

        val interfaceTypeCount = schema.additionalTypes.count { it is GraphQLInterfaceType }
        assertEquals(interfaceTypeCount, 2)
    }

    class MultipleInterfaces : GraphQLQueryResolver {
        fun query1(): NamedResourceImpl? = null
        fun query2(): VersionedResourceImpl? = null

        class NamedResourceImpl : NamedResource {
            override fun name(): String? = null
        }

        class VersionedResourceImpl : VersionedResource {
            override fun version(): Int? = null
        }
    }

    interface NamedResource {
        fun name(): String?
    }

    interface VersionedResource {
        fun version(): Int?
    }

    @Test
    fun `scanner handles interface implementation that is not used as field type`() {
        val schema = SchemaParser.newParser()
            // uncommenting the line below makes the test succeed
            .dictionary(InterfaceImplementation.NamedResourceImpl::class)
            .resolvers(InterfaceImplementation())
            .schemaString(
                """
                type Query {
                    query1: NamedResource
                }

                interface NamedResource {
                    name: String!
                }

                type NamedResourceImpl implements NamedResource {
                    name: String!
                }
                """)
            .build()
            .makeExecutableSchema()

        val interfaceTypeCount = schema.additionalTypes.count { it is GraphQLInterfaceType }
        assertEquals(interfaceTypeCount, 1)
    }

    class InterfaceImplementation : GraphQLQueryResolver {
        fun query1(): NamedResource? = null

        fun query2(): NamedResourceImpl? = null

        class NamedResourceImpl : NamedResource {
            override fun name(): String? = null
        }
    }

    @Test
    fun `scanner handles custom scalars when matching input types`() {
        val customMap = GraphQLScalarType.newScalar()
            .name("customMap")
            .coercing(object : Coercing<Map<String, Any>, Map<String, Any>> {
                override fun serialize(dataFetcherResult: Any, context: GraphQLContext, locale: Locale): Map<String, Any> = mapOf()
                override fun parseValue(input: Any, context: GraphQLContext, locale: Locale): Map<String, Any> = mapOf()
                override fun parseLiteral(input: Value<*>, variables: CoercedVariables, context: GraphQLContext, locale: Locale): Map<String, Any> = mapOf()
            }).build()

        val schema = SchemaParser.newParser()
            .resolvers(object : GraphQLQueryResolver {
                fun hasRawScalar(rawScalar: Map<String, Any>): Boolean = true
                fun hasMapField(mapField: HasMapField): Boolean = true
            })
            .scalars(customMap)
            .schemaString(
                """
                type Query {
                    hasRawScalar(customMap: customMap): Boolean
                    hasMapField(mapField: HasMapField): Boolean
                }

                input HasMapField {
                    map: customMap
                }

                scalar customMap
                """)
            .build()
            .makeExecutableSchema()

        assert(schema.typeMap.containsKey("customMap"))
    }

    class HasMapField {
        var map: Map<String, Any>? = null
    }

    @Test
    fun `scanner allows class to be used for object type and input object type`() {
        val schema = SchemaParser.newParser()
            .resolvers(object : GraphQLQueryResolver {
                fun test(pojo: Pojo): Pojo = pojo
            })
            .schemaString(
                """
                type Query {
                    test(inPojo: InPojo): OutPojo
                }

                input InPojo {
                    name: String
                }

                type OutPojo {
                    name: String
                }
                """)
            .build()
            .makeExecutableSchema()

        val typeCount = schema.additionalTypes.count()
        assertEquals(typeCount, 2)
    }

    class Pojo {
        var name: String? = null
    }

    @Test
    fun `scanner should handle nested types in input types`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                schema {
                    query: Query
                }
                
                type Query {
                    animal: Animal
                }
                
                interface Animal {
                    type: ComplexType
                }
                
                type Dog implements Animal {
                    type: ComplexType
                }
                
                type ComplexType {
                    id: String
                }
                """)
            .resolvers(NestedInterfaceTypeQuery())
            .dictionary(NestedInterfaceTypeQuery.Dog::class)
            .build()
            .makeExecutableSchema()

        val typeCount = schema.additionalTypes.count()
        assertEquals(typeCount, 3)
    }

    class NestedInterfaceTypeQuery : GraphQLQueryResolver {
        fun animal(): Animal? = null

        class Dog : Animal {
            override fun type(): ComplexType? = null
        }

        class ComplexType {
            var id: String? = null
        }
    }

    @Test
    fun `scanner should handle unused types when option is true`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                # these directives are defined in the Apollo Federation Specification: 
                # https://www.apollographql.com/docs/apollo-server/federation/federation-spec/
                scalar FieldSet
                scalar link__Import
                enum link__Purpose { SECURITY EXECUTION }
                directive @key(fields: FieldSet!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE
                directive @extends on OBJECT | INTERFACE
                directive @external on FIELD_DEFINITION | OBJECT
                directive @link(url: String!, as: String, for: link__Purpose, import: [link__Import]) repeatable on SCHEMA

                extend schema @link(url: "https://specs.apollo.dev/federation/v2.0", import: ["@key", "@shareable"])

                # Let's say this is the Products service from Apollo Federation Introduction
                type Query {
                    allProducts: [Product]
                }
                
                type Product {
                    name: String
                }
                
                type User @key(fields: "id") @extends {
                    id: ID! @external
                    recentPurchasedProducts: [Product]
                    address: Address
                }
                
                type Address {
                    street: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun allProducts(): List<Product>? = null
            })
            .options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build())
            .dictionary(User::class)
            .dictionary("link__Purpose", LinkPurpose::class)
            .scalars(fieldSetScalar, linkImportScalar)
            .build()
            .makeExecutableSchema()

        val objectTypes = schema.additionalTypes.filterIsInstance<GraphQLObjectType>()
        assert(objectTypes.any { it.name == "User" })
        assert(objectTypes.any { it.name == "Address" })
    }

    data class FieldSet(val value: String)
    enum class LinkPurpose { SECURITY, EXECUTION }

    private val fieldSetScalar: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("FieldSet")
        .coercing(object : Coercing<FieldSet, String> {
            override fun serialize(input: Any, context: GraphQLContext, locale: Locale) = input.toString()
            override fun parseValue(input: Any, context: GraphQLContext, locale: Locale) =
                FieldSet(input.toString())
            override fun parseLiteral(input: Value<*>, variables: CoercedVariables, context: GraphQLContext, locale: Locale) =
                FieldSet(input.toString())
        })
        .build()

    private val linkImportScalar: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("link__Import")
        .coercing(object : Coercing<String, String> {
            override fun serialize(input: Any, context: GraphQLContext, locale: Locale) = input.toString()
            override fun parseValue(input: Any, context: GraphQLContext, locale: Locale) = input.toString()
            override fun parseLiteral(input: Value<*>, variables: CoercedVariables, context: GraphQLContext, locale: Locale) =
                input.toString()
        })
        .build()

    class Product {
        var name: String? = null
    }

    class User {
        var id: String? = null
        var recentPurchasedProducts: List<Product>? = null
        var address: Address? = null
    }

    class Address {
        var street: String? = null
    }

    @Test
    fun `scanner should handle unused types with interfaces, unions and enums when option is true`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    whatever: Whatever
                }

                type Whatever {
                    value: String
                }

                type Unused {
                    someInterface: SomeInterface
                }

                interface SomeInterface {
                    value: String
                }

                type Implementation implements SomeInterface {
                    value: String
                }

                interface OtherInterface {
                    value: String
                }

                union SomeUnion = Unused | Implementation

                enum SomeEnum {
                    A
                    B
                }

                enum OtherEnum {
                    A
                    B
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun whatever(): Whatever? = null
            })
            .options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build())
            .dictionary(Unused::class, Implementation::class, SomeEnum::class)
            .build()
            .makeExecutableSchema()

        val objectTypes = schema.additionalTypes.filterIsInstance<GraphQLObjectType>()
        val interfaceTypes = schema.additionalTypes.filterIsInstance<GraphQLInterfaceType>()
        assert(objectTypes.any { it.name == "Unused" })
        assert(objectTypes.any { it.name == "Implementation" })
        assert(interfaceTypes.any { it.name == "SomeInterface" })
        assert(schema.getType("OtherInterface") is GraphQLInterfaceType)
        assert(schema.getType("SomeUnion") is GraphQLUnionType)
        assert(schema.getType("SomeEnum") is GraphQLEnumType)
        assert(schema.getType("OtherEnum") == null)
    }

    @Test
    fun `scanner should handle unused enum used as an argument of a missing resolver when option is true`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    whatever: Whatever
                    preview(value: SomeEnum): String
                }

                type Whatever {
                    value: String
                }

                enum SomeEnum {
                    A
                    B
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun whatever(): Whatever? = null
            })
            .options(SchemaParserOptions.newOptions()
                .includeUnusedTypes(true)
                .missingResolverDataFetcherProvider { _, _ -> DataFetcher<Any?> { null } }
                .build())
            .dictionary(SomeEnum::class)
            .build()
            .makeExecutableSchema()

        assert(schema.queryType.getFieldDefinition("preview").getArgument("value").type is GraphQLEnumType)
    }

    class Whatever {
        var value: String? = null
    }

    enum class SomeEnum { A, B }

    class Unused {
        var someInterface: SomeInterface? = null
    }

    class Implementation : SomeInterface {
        override fun getValue(): String? {
            return null
        }
    }

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
    fun `scanner handles unions used only by fields of interfaces implemented by other interfaces`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    named: NamedNode
                }

                interface Node {
                    content: Content
                }

                interface NamedNode implements Node {
                    content: Text
                    name: String
                }

                type Document implements NamedNode & Node {
                    content: Text
                    name: String
                }

                type Text {
                    text: String
                }

                type Image {
                    url: String
                }

                union Content = Text | Image
                """)
            .resolvers(NestedInterfaceUnion())
            .dictionary(NestedInterfaceUnion.Document::class, NestedInterfaceUnion.Image::class)
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Content") as GraphQLUnionType).types.map { it.name }.toSet(), setOf("Text", "Image"))
    }

    class NestedInterfaceUnion : GraphQLQueryResolver {
        fun named(): NamedNode? = null

        interface NamedNode

        class Document(val content: Text?, val name: String?) : NamedNode

        class Text(val text: String?)

        class Image(val url: String?)
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

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                item {
                    content {
                        ... on Text { text }
                    }
                }
                other {
                    ... on Holder {
                        image { url }
                        content {
                            ... on Image { url }
                        }
                    }
                }
            }
            """
        }

        assertEquals(data["item"], mapOf("content" to mapOf("text" to "x")))
        assertEquals(data["other"], mapOf("image" to mapOf("url" to "u"), "content" to mapOf("url" to "v")))
    }

    class DictionaryReachableUnion : GraphQLQueryResolver {
        fun item(): Item = Book(Text("x"))
        fun other(): OtherUnion = Holder(Image("u"), Image("v"))

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

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                item {
                    content {
                        ... on Text { text }
                    }
                }
            }
            """
        }

        assertEquals(data["item"], mapOf("content" to mapOf("text" to "t")))
    }

    class UnusedTypesReachableUnion : GraphQLQueryResolver {
        fun item(): Item = Book(Text("t"))

        interface Item

        interface Content

        class Book(val content: Text?) : Item

        class Text(val text: String?) : Content

        class Image(val url: String?, val caption: Caption?) : Content

        class Caption(val value: String?) : Content
    }
}
