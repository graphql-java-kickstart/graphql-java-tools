package com.coxautodev.graphql.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.relay.Connection
import graphql.relay.SimpleListConnection
import graphql.schema.DataFetcher
import graphql.schema.DataFetcherFactories
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment
import spock.lang.Specification

class RelayConnectionSpec extends Specification {

    def "relay connection types are compatible"() {
        when:
            GraphQLSchema schema = SchemaParser.newParser().schemaString('''\
                        directive @uppercase on FIELD_DEFINITION
                        
                        type Query {
                            users(first: Int, after: String): UserConnection
                            otherTypes: AnotherTypeConnection
                        }
                        
                        type UserConnection {
                            edges: [UserEdge!]!
                            pageInfo: PageInfo!
                        }
                        
                        type UserEdge {
                            node: User!
                        }
                        
                        
                        type User {
                            id: ID!
                            name: String @uppercase
                        }
                        
                        type PageInfo {
                        }
                        
                        type AnotherTypeConnection {
                            edges: [AnotherTypeEdge!]!
                        }
                        
                        type AnotherTypeEdge {
                            node: AnotherType!
                        }
                        
                        type AnotherType {
                            echo: String
                        }
                    ''')
                    .resolvers(new QueryResolver())
                    .directive("uppercase", new UppercaseDirective())
                    .build()
                    .makeExecutableSchema()
            GraphQL gql = GraphQL.newGraphQL(schema)
                    .queryExecutionStrategy(new AsyncExecutionStrategy())
                    .build()
            def data = Utils.assertNoGraphQlErrors(gql, [limit: 10]) {
                '''
                query {
                    users {
                        edges {
                            node {
                                id
                                name
                            }
                        }
                    }
                    otherTypes {
                        edges {
                            node {
                                echo
                            }
                        }
                    }
                }
                '''
            }

        then:
            noExceptionThrown()
            data.users.edges.size == 1
            data.users.edges[0].node.id == "1"
            data.users.edges[0].node.name == "name"
            data.otherTypes.edges.size == 1
            data.otherTypes.edges[0].node.echo == "echo"
    }

    static class QueryResolver implements GraphQLQueryResolver {
        Connection<User> users(int first, String after, DataFetchingEnvironment env) {
            new SimpleListConnection<User>(new ArrayList([new User(1L, "name")])).get(env)
        }

        Connection<AnotherType> otherTypes(DataFetchingEnvironment env) {
            new SimpleListConnection<AnotherType>(new ArrayList([new AnotherType("echo")])).get(env)
        }
    }

    static class User {
        Long id
        String name

        User(Long id, String name) {
            this.id = id
            this.name = name
        }
    }

    private static class AnotherType {
        String echo

        AnotherType(String echo) {
            this.echo = echo
        }
    }

    static class UppercaseDirective implements SchemaDirectiveWiring {

        @Override
        GraphQLFieldDefinition onField(SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition> env) {
            GraphQLFieldDefinition field = env.getElement();
            DataFetcher dataFetcher = DataFetcherFactories.wrapDataFetcher(field.getDataFetcher(), {
                dataFetchingEnvironment, value ->
                    if (value == null) {
                        return null
                    }
                    return  ((String) value).toUpperCase()
            })
            return field.transform({ builder -> builder.dataFetcher(dataFetcher) });
        }
    }


}
