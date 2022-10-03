package graphql.kickstart.tools

import graphql.schema.*
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.CompletableFuture

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
                    override fun serialize(dataFetcherResult: Any): Any? = null
                    override fun parseValue(input: Any): Any = input
                    override fun parseLiteral(input: Any): Any = input
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
                override fun serialize(dataFetcherResult: Any): Map<String, Any> = mapOf()
                override fun parseValue(input: Any): Map<String, Any> = mapOf()
                override fun parseLiteral(input: Any): Map<String, Any> = mapOf()
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
    @Ignore("TODO remove this once directives are fully replaced with applied directives OR issue #664 is resolved")
    fun `scanner should handle unused types when option is true`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                # Let's say this is the Products service from Apollo Federation Introduction

                type Query {
                    allProducts: [Product]
                }
                
                type Product {
                    name: String
                }
                
                # these directives are defined in the Apollo Federation Specification: 
                # https://www.apollographql.com/docs/apollo-server/federation/federation-spec/
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
            .build()
            .makeExecutableSchema()

        val objectTypes = schema.additionalTypes.filterIsInstance<GraphQLObjectType>()
        assert(objectTypes.any { it.name == "User" })
        assert(objectTypes.any { it.name == "Address" })
    }

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
    fun `scanner should handle unused types with interfaces when option is true`() {
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
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun whatever(): Whatever? = null
            })
            .options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build())
            .dictionary(Unused::class, Implementation::class)
            .build()
            .makeExecutableSchema()

        val objectTypes = schema.additionalTypes.filterIsInstance<GraphQLObjectType>()
        val interfaceTypes = schema.additionalTypes.filterIsInstance<GraphQLInterfaceType>()
        assert(objectTypes.any { it.name == "Unused" })
        assert(objectTypes.any { it.name == "Implementation" })
        assert(interfaceTypes.any { it.name == "SomeInterface" })
    }

    class Whatever {
        var value: String? = null
    }

    class Unused {
        var someInterface: SomeInterface? = null
    }

    class Implementation : SomeInterface {
        override fun getValue(): String? {
            return null
        }
    }
}
