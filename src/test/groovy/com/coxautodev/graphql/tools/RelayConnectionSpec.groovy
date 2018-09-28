package com.coxautodev.graphql.tools

import graphql.relay.Connection
import graphql.relay.DefaultConnection
import graphql.relay.Edge
import graphql.relay.SimpleListConnection
import graphql.schema.DataFetchingEnvironment
import spock.lang.Specification

class RelayConnectionSpec extends Specification {

    private static final SchemaParserOptions options = SchemaParserOptions.newOptions().genericWrappers(
            new SchemaParserOptions.GenericWrapper(
                    Connection.class,
                    0
            ),
            new SchemaParserOptions.GenericWrapper(
                    Edge.class,
                    0
            )
    ).build()

    def "relay connection types are compatible"() {
        when:
            SchemaParser.newParser().options(options).schemaString('''\
                        type Query {
                            users(first: Int, after: String): UserConnection
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
                    ''')
                    .resolvers(new QueryResolver())
                    .dictionary(User.class)
                    .build()
                    .makeExecutableSchema()

        then:
            noExceptionThrown()
    }

    static class QueryResolver implements GraphQLQueryResolver {
        Connection<User> users(int first, String after, DataFetchingEnvironment env) {
            new SimpleListConnection<User>(new ArrayList()).get(env)
        }
    }

    static class User {
        Long id
        String name
    }


}
