package com.coxautodev.graphql.tools

import graphql.relay.Connection
import graphql.relay.DefaultConnection
import graphql.relay.Edge
import graphql.relay.SimpleListConnection
import graphql.schema.DataFetchingEnvironment
import spock.lang.Specification

class RelayConnectionSpec extends Specification {

    def "relay connection types are compatible"() {
        when:
            SchemaParser.newParser().schemaString('''\
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
                            name: String
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
                        
                        }
                    ''')
                    .resolvers(new QueryResolver())
                    .build()
                    .makeExecutableSchema()

        then:
            noExceptionThrown()
    }

    static class QueryResolver implements GraphQLQueryResolver {
        Connection<User> users(int first, String after, DataFetchingEnvironment env) {
            new SimpleListConnection<User>(new ArrayList()).get(env)
        }

        Connection<AnotherType> otherTypes(DataFetchingEnvironment env) {
            new SimpleListConnection<AnotherType>(new ArrayList()).get(env)
        }
    }

    static class User {
        Long id
        String name
    }

    private static class AnotherType {

    }


}
