package com.coxautodev.graphql.tools;

import graphql.relay.Connection;
import graphql.relay.SimpleListConnection;
import graphql.schema.DataFetchingEnvironment;
import org.junit.Test;

import java.util.ArrayList;

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
        public Connection<User> users(int first, String after, DataFetchingEnvironment env) {
            return new SimpleListConnection<User>(new ArrayList<>()).get(env);
        }
    }

    static class User {
        Long id;
        String name;
    }
}
