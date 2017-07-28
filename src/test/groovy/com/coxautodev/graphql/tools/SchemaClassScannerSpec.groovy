package com.coxautodev.graphql.tools

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
                .build()
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
                .build()
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
                .build()

        then:
            noExceptionThrown()
    }

    private class ScalarDuplicateQuery implements GraphQLQueryResolver {
        String string() { "" }
        String id() { "" }
    }

    def "scanner handles interfaces referenced by objects that aren't explicitly used"() {
        when:
            SchemaParser.newParser()
                .resolvers(new InterfaceMissingQuery())
                .schemaString("""
                    interface Interface {
                        id: ID!
                    }
                    
                    type Query implements Interface {
                        id: ID!
                    }
                """)
                .build()
                .parseSchemaObjects()

        then:
            noExceptionThrown()
    }

    private class InterfaceMissingQuery implements GraphQLQueryResolver {
        String id() { "" }
    }

    def "scanner handles input types that reference other input types"() {
        when:
            SchemaParser.newParser()
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
                .build()
                .makeExecutableSchema()

        then:
            noExceptionThrown()
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

    def "scanner allows multiple return types for custom scalars"() {
        when:
            SchemaParser.newParser()
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
                .build()

        then:
            noExceptionThrown()
    }

    class ScalarsWithMultipleTypes implements GraphQLQueryResolver {
        Integer first() { null }
        String second() { null }
    }

    def "scanner handles multiple interfaces that are not used as field types"() {
        when:
            SchemaParser.newParser()
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
                .build()

        then:
            noExceptionThrown()
    }

    class MultipleInterfaces implements GraphQLQueryResolver {
        NamedResourceImpl query1() { null }
        VersionedResourceImpl query2() { null }

        static interface NamedResource {
            String name()
        }
        static interface VersionedResource {
            int version()
        }

        static class NamedResourceImpl implements NamedResource {
            String name() {}
        }
        static class VersionedResourceImpl implements VersionedResource {
            int version() {}
        }
    }
}
