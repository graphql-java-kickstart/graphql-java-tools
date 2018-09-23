package com.coxautodev.graphql.tools

import graphql.relay.Connection
import graphql.relay.DefaultConnection
import graphql.relay.SimpleListConnection
import graphql.schema.DataFetchingEnvironment
import spock.lang.Specification

class RelayConnectionSpec extends Specification {

    def "relay connection types are compatible"() {
        when:
            SchemaParser.newParser().schemaString('''\
                        type Query {
                            users(first: Int, after: String): UserConnection
                        }
                        
                        type UserConnection {
                            edges: [UserEdge!]!
                            pageInfo: PageInfo!
                        }
                        
                        type UserEdge {
                            cursor: String!
                            node: User!
                        }
                        
                        type User {
                            id: ID!
                            name: String
                        }
                    ''')
                    .resolvers(new QueryResolver())
                    .dictionary(User.class)
                    .build()
                    .makeExecutableSchema()

        then:
            noExceptionThrown()
    }

    static class QueryResolver implements GraphQLQueryResolver {
        DefaultConnection<User> users(int first, String after, DataFetchingEnvironment env) {
            new SimpleListConnection<User>(new ArrayList()).get(env) as DefaultConnection<User>
        }
    }

    static class User {
        Long id
        String name
    }


}
