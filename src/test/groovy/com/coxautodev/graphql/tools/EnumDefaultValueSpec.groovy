package com.coxautodev.graphql.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLSchema
import spock.lang.Specification

class EnumDefaultValueSpec extends Specification {

    def "enumvalue is not passed down to graphql-java"() {
        when:
            GraphQLSchema schema = SchemaParser.newParser().schemaString('''\
                        type Query {
                            test(input: MySortSpecifier): SortBy
                        }
                        input MySortSpecifier {
                            sortBy: SortBy = createdOn
                            value: Int = 10
                        }
                        enum SortBy {
                            createdOn
                            updatedOn
                        }
                    ''').resolvers(new GraphQLQueryResolver() {

                            SortBy test(MySortSpecifier input) {
                                return input.sortBy
                            }

                        })
                    .build()
                    .makeExecutableSchema()
            GraphQL gql = GraphQL.newGraphQL(schema)
                    .queryExecutionStrategy(new AsyncExecutionStrategy())
                    .build()
            def data = Utils.assertNoGraphQlErrors(gql, [input: [value: 1]]) {
                '''
                query test($input: MySortSpecifier) {
                    test(input: $input)
                }
                '''
            }

        then:
            noExceptionThrown()
            data.test == 'createdOn'
    }

    static class MySortSpecifier {
        SortBy sortBy
        int value
    }

    enum SortBy {
        createdOn,
        updatedOn
    }

}
