package com.coxautodev.graphql.tools;

import graphql.relay.*;
import graphql.schema.DataFetchingEnvironment;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class RelayConnectionTest {

    @Test
    public void compiles() {
        SchemaParser.newParser().file("RelayConnection.graphqls")
                .resolvers(new QueryResolver())
                .dictionary(User.class)
                .build()
                .makeExecutableSchema();
    }

    static class QueryResolver implements GraphQLQueryResolver {
        // fixme #114: desired return type to use: Connection<User>
        public UserConnection users(int first, String after, DataFetchingEnvironment env) {
            return (UserConnection) new SimpleListConnection<User>(new ArrayList()).get(env);
        }
    }

    // fixme #114: remove this implementation
    static class UserConnection extends DefaultConnection<User> {

        UserConnection(List<Edge<User>> edges, PageInfo pageInfo) {
            super(edges, pageInfo);
        }

        public List<UserEdge> edges() {
            return super.getEdges().stream()
                    .map(UserEdge.class::cast)
                    .collect(Collectors.toList());
        }

        public PageInfo getPageInfo() {
            return super.getPageInfo();
        }
    }

    // fixme #114: remove this implementation
    static class UserEdge extends DefaultEdge<User> {

        UserEdge(User node, ConnectionCursor cursor) {
            super(node, cursor);
        }

    }

    static class User {
        Long id;
        String name;
    }
}
