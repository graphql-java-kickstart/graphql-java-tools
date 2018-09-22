package com.coxautodev.graphql.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLSchema
import spock.lang.Shared
import spock.lang.Specification

class ParameterizedGetterSpec extends Specification {

    @Shared
    GraphQL gql

    def setupSpec() {
        GraphQLSchema schema = SchemaParser.newParser().schemaString('''\
                        type Query {
                            human: Human
                        }
                        
                        type Human {
                            bestFriends: [Character!]!
                            allFriends(limit: Int!): [Character!]!
                        }
                        
                        type Character {
                            name: String!
                        }
                    '''.stripIndent())
                .resolvers(new QueryResolver(), new HumanResolver())
                .build()
                .makeExecutableSchema()
        gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(new AsyncExecutionStrategy())
                .build()
    }

    def "parameterized query is resolved on data type instead of on its resolver"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql, [limit: 10]) {
                '''
                query allFriends($limit: Int!) {
                    human {
                        allFriends(limit: $limit) {
                            name
                        }
                    }
                }
                '''
            }

        then:
            data.human
    }

    class QueryResolver implements GraphQLQueryResolver {
        Human human() { new Human() }
    }

    class Human {
        List<Character> allFriends(int limit) { Collections.emptyList() }
    }

    class HumanResolver implements GraphQLResolver<Human> {
        List<Character> bestFriends(Human human) { Collections.emptyList() }
    }

    class Character {
        String name
    }
}
