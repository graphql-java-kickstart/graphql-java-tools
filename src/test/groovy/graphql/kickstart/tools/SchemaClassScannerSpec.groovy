package graphql.kickstart.tools

import graphql.language.InputObjectTypeDefinition
import graphql.language.InputObjectTypeExtensionDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.ScalarTypeDefinition
import graphql.schema.Coercing
import graphql.schema.GraphQLScalarType
import spock.lang.Specification

import java.util.concurrent.CompletableFuture

/**
 * @author Andrew Potter
 */
class SchemaClassScannerSpec extends Specification {

    def "scanner handles futures and immediate return types"() {
        when:
        SchemaParser.newParser()
                .resolvers(new FutureImmediateQuery())
                .schemaString("""
                    type Query {
                        future: Int!
                        immediate: Int!
                    }
                """)
                .scan()
        then:
        noExceptionThrown()
    }

    private class FutureImmediateQuery implements GraphQLQueryResolver {
        CompletableFuture<Integer> future() {
            CompletableFuture.completedFuture(1)
        }

        Integer immediate() {
            1
        }
    }

    def "scanner handles primitive and boxed return types"() {
        when:
        SchemaParser.newParser()
                .resolvers(new PrimitiveBoxedQuery())
                .schemaString("""
                    type Query {
                        primitive: Int!
                        boxed: Int!
                    }
                """)
                .scan()
        then:
        noExceptionThrown()
    }

    private class PrimitiveBoxedQuery implements GraphQLQueryResolver {
        int primitive() {
            1
        }

        Integer boxed() {
            1
        }
    }

    def "scanner handles different scalars with same java class"() {
        when:
        SchemaParser.newParser()
                .resolvers(new ScalarDuplicateQuery())
                .schemaString("""
                    type Query {
                        string: String!
                        id: ID!
                    }
                """)
                .scan()

        then:
        noExceptionThrown()
    }

    private class ScalarDuplicateQuery implements GraphQLQueryResolver {
        String string() { "" }

        String id() { "" }
    }

    def "scanner handles interfaces referenced by objects that aren't explicitly used"() {
        when:
        ScannedSchemaObjects objects = SchemaParser.newParser()
                .resolvers(new InterfaceMissingQuery())
                .schemaString("""
                    interface Interface {
                        id: ID!
                    }
                    
                    type Query implements Interface {
                        id: ID!
                    }
                """)
                .scan()

        then:
        objects.definitions.find { it instanceof InterfaceTypeDefinition } != null
    }

    private class InterfaceMissingQuery implements GraphQLQueryResolver {
        String id() { "" }
    }

    def "scanner handles input types that reference other input types"() {
        when:
        ScannedSchemaObjects objects = SchemaParser.newParser()
                .resolvers(new MultipleInputTypeQuery())
                .schemaString("""
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
                .scan()

        then:
        objects.definitions.findAll { it instanceof InputObjectTypeDefinition }.size() == 3

    }

    private class MultipleInputTypeQuery implements GraphQLQueryResolver {

        String test(FirstInput input) { "" }

        class FirstInput {
            String id

            SecondInput second() { new SecondInput() }
            ThirdInput third
        }

        class SecondInput {
            String id
        }

        class ThirdInput {
            String id
        }
    }

    def "scanner handles input types extensions"() {
        when:
        ScannedSchemaObjects objects = SchemaParser.newParser()
                .schemaString('''
                    type Query { }

                    type Mutation {
                        save(input: UserInput!): Boolean
                    }
                    
                    input UserInput {
                        name: String                        
                    }
                    
                    extend input UserInput {
                        password: String
                    }
                ''')
                .resolvers(
                        new GraphQLMutationResolver() {
                            boolean save(Map map) { true }
                        },
                        new GraphQLQueryResolver() {}
                )
                .scan()

        then:
        objects.definitions.findAll { (it.class == InputObjectTypeExtensionDefinition.class) }.size() == 1
        objects.definitions.findAll { (it.class == InputObjectTypeDefinition.class) }.size() == 1
    }

    def "scanner allows multiple return types for custom scalars"() {
        when:
        ScannedSchemaObjects objects = SchemaParser.newParser()
                .resolvers(new ScalarsWithMultipleTypes())
                .scalars(new GraphQLScalarType("UUID", "Test scalars with duplicate types", new Coercing() {
                    @Override
                    Object serialize(Object input) {
                        return null
                    }

                    @Override
                    Object parseValue(Object input) {
                        return null
                    }

                    @Override
                    Object parseLiteral(Object input) {
                        return null
                    }
                }))
                .schemaString("""
                    scalar UUID
                    
                    type Query {
                        first: UUID
                        second: UUID
                    }
                """)
                .scan()

        then:
        objects.definitions.findAll { it instanceof ScalarTypeDefinition }.size() == 1
    }

    class ScalarsWithMultipleTypes implements GraphQLQueryResolver {
        Integer first() { null }

        String second() { null }
    }

    def "scanner handles multiple interfaces that are not used as field types"() {
        when:
        ScannedSchemaObjects objects = SchemaParser.newParser()
                .resolvers(new MultipleInterfaces())
                .schemaString("""
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
                .scan()

        then:
        objects.definitions.findAll { it instanceof InterfaceTypeDefinition }.size() == 2
    }

    def "scanner handles interface implementation that is not used as field type"() {
        when:
        ScannedSchemaObjects objects = SchemaParser.newParser()
        // uncommenting the line below makes the test succeed
                .dictionary(InterfaceImplementation.NamedResourceImpl.class)
                .resolvers(new InterfaceImplementation())
                .schemaString("""
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
                .scan()

        then:
        objects.definitions.findAll { it instanceof InterfaceTypeDefinition }.size() == 1
    }

    def "scanner handles custom scalars when matching input types"() {
        when:
        GraphQLScalarType customMap = new GraphQLScalarType('customMap', '', new Coercing<Map<String, Object>, Map<String, Object>>() {
            @Override
            Map<String, Object> serialize(Object dataFetcherResult) {
                return [:]
            }

            @Override
            Map<String, Object> parseValue(Object input) {
                return [:]
            }

            @Override
            Map<String, Object> parseLiteral(Object input) {
                return [:]
            }
        })

        ScannedSchemaObjects objects = SchemaParser.newParser()
                .resolvers(new GraphQLQueryResolver() {
                    boolean hasRawScalar(Map<String, Object> rawScalar) { true }

                    boolean hasMapField(HasMapField mapField) { true }
                })
                .scalars(customMap)
                .schemaString("""
                    type Query {
                        hasRawScalar(customMap: customMap): Boolean
                        hasMapField(mapField: HasMapField): Boolean
                    }
                    
                    input HasMapField {
                        map: customMap
                    }
                    
                    scalar customMap
                """)
                .scan()

        then:
        objects.definitions.findAll { it instanceof ScalarTypeDefinition }.size() == 2 // Boolean and customMap
    }

    class HasMapField {
        Map<String, Object> map
    }

    def "scanner allows class to be used for object type and input object type"() {
        when:
        ScannedSchemaObjects objects = SchemaParser.newParser()
                .resolvers(new GraphQLQueryResolver() {
                    Pojo test(Pojo pojo) { pojo }
                })
                .schemaString("""
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
                .scan()

        then:
        objects.definitions
    }

    class Pojo {
        String name
    }

    def "scanner should handle nested types in input types"() {
        when:
        ScannedSchemaObjects objects = SchemaParser.newParser()
                .schemaString(''' 
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
                ''')
                .resolvers(new NestedInterfaceTypeQuery())
                .dictionary(NestedInterfaceTypeQuery.Dog)
                .scan()

        then:
        objects.definitions.findAll { it instanceof ObjectTypeDefinition }.size() == 3

    }

    class NestedInterfaceTypeQuery implements GraphQLQueryResolver {
        Animal animal() { null }

        class Dog implements Animal {
            @Override
            ComplexType type() { null }
        }

        class ComplexType {
            String id
        }
    }

    def "scanner should handle unused types when option is true"() {
        when:
        ScannedSchemaObjects objects = SchemaParser.newParser()
                .schemaString(''' 
                    # Let's say this is the Products service from Apollo Federation Introduction

                    type Query {
                        allProducts: [Product]
                    }
                    
                    type Product {
                        name: String
                    }
                    
                    #these directives are defined in the Apollo Federation Specification: https://www.apollographql.com/docs/apollo-server/federation/federation-spec/
                    type User @key(fields: "id") @extends {
                        id: ID! @external
                        recentPurchasedProducts: [Product]
                        address: Address
                    }
                    
                    type Address {
                        street: String
                    }
                ''')
                .resolvers(new GraphQLQueryResolver() {
                    List<Product> allProducts() { null }
                })
                .options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build())
                .dictionary(User)
                .scan()

        then:
        objects.definitions.find { it.name == "User" } != null
        objects.definitions.find { it.name == "Address" } != null
    }

    class Product {
        String name
    }

    class User {
        String id
        List<Product> recentPurchasedProducts
        Address address
    }

    class Address {
        String street
    }

    def "scanner should handle unused types with interfaces when option is true"() {
        when:
        ScannedSchemaObjects objects = SchemaParser.newParser()
                .schemaString('''
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
                ''')
                .resolvers(new GraphQLQueryResolver() {
                    Whatever whatever() { null }
                })
                .options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build())
                .dictionary(Unused, Implementation)
                .scan()

        then:
        objects.definitions.find { it.name == "Unused" } != null
        objects.definitions.find { it.name == "SomeInterface" } != null
        objects.definitions.find { it.name == "Implementation" } != null
    }

    class Whatever {
        String value
    }

    class Unused {
        SomeInterface someInterface
    }

    class Implementation implements SomeInterface {
        @Override
        String getValue() {
            return null
        }
    }
}
