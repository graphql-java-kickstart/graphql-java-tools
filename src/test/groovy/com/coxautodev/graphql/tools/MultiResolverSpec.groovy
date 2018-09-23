package com.coxautodev.graphql.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLSchema
import spock.lang.Shared
import spock.lang.Specification

class MultiResolverSpec extends Specification {

    @Shared
    GraphQL gql

    def setupSpec() {
        GraphQLSchema schema = SchemaParser.newParser().schemaString('''\
                        type Query {
                            person: Person
                        }
                        
                        type Person {
                            name: String!
                            friends(friendName: String!): [Friend!]!
                        }
                        
                        type Friend {
                            name: String!
                        }
                    '''.stripIndent())
                .resolvers(new QueryWithPersonResolver(), new PersonFriendResolver(), new PersonNameResolver())
                .build()
                .makeExecutableSchema()
        gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(new AsyncExecutionStrategy())
                .build()
    }

    def "multiple resolvers for one data class should resolve methods with arguments"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql, [friendName: "name"]) {
                '''
                query friendOfPerson($friendName: String!) {
                    person {
                        friends(friendName: $friendName) {
                            name
                        }
                    }
                }
                '''
            }

        then:
            data.person
    }

    class QueryWithPersonResolver implements GraphQLQueryResolver {
        Person getPerson() {
            new Person()
        }
    }

    class Person {

    }

    class Friend {
        String name
    }

    class PersonFriendResolver implements GraphQLResolver<Person> {
        List<Friend> friends(Person person, String friendName) {
            Collections.emptyList()
        }
    }

    class PersonNameResolver implements GraphQLResolver<Person> {
        String name(Person person) {
            "name"
        }
    }
}
