package com.coxautodev.graphql.tools

import spock.lang.Specification

import java.util.concurrent.CompletableFuture

/**
 * @author Andrew Potter
 */
class SchemaClassScannerSpec extends Specification {

    def "test scanning futures and immediate return types"() {
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
                .makeExecutableSchema()
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

    def "test scanning primitive and boxed return types"() {
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
                .makeExecutableSchema()
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
}
