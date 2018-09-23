package com.coxautodev.graphql.tools


import graphql.relay.ConnectionCursor
import graphql.relay.DefaultConnection
import graphql.relay.DefaultEdge
import graphql.relay.Edge
import graphql.relay.PageInfo
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
        // fixme #114: desired return type to use: Connection<User>
        UserConnection users(int first, String after, DataFetchingEnvironment env) {
            new SimpleListConnection<User>(new ArrayList()).get(env) as UserConnection
        }
    }

    // fixme #114: remove this implementation
    static class UserConnection extends DefaultConnection<User> {

        UserConnection(List<Edge<User>> edges, PageInfo pageInfo) {
            super(edges, pageInfo)
        }

        List<UserEdge> getEdges() {
            this.edges
        }

        PageInfo getPageInfo() {
            this.pageInfo
        }
    }

    // fixme #114: remove this implementation
    static class UserEdge extends DefaultEdge<User> {

        UserEdge(User node, ConnectionCursor cursor) {
            super(node, cursor)
        }

    }

    static class User {
        Long id
        String name
    }


}
