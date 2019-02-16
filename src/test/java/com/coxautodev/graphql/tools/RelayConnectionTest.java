package com.coxautodev.graphql.tools;

import graphql.GraphQL;
import graphql.execution.AsyncExecutionStrategy;
import graphql.relay.Connection;
import graphql.relay.SimpleListConnection;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import groovy.lang.Closure;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class RelayConnectionTest {

    @Test
    public void compiles() {
        GraphQLSchema schema = SchemaParser.newParser().file("RelayConnection.graphqls")
                .resolvers(new QueryResolver())
                .dictionary(User.class)
                .build()
                .makeExecutableSchema();

        GraphQL gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(new AsyncExecutionStrategy())
                .build();

        Map<String, Object> variables = new HashMap<>();
        variables.put("limit", 10);
        Utils.assertNoGraphQlErrors(gql, variables, new Closure<String>(null) {
            @Override
            public String call() {
                return "query {\n" +
                        "   users {\n" +
                        "       edges {\n" +
                        "           cursor\n" +
                        "           node {\n" +
                        "               id\n" +
                        "               name\n" +
                        "           }\n" +
                        "       },\n" +
                        "       pageInfo {\n" +
                        "           hasPreviousPage,\n" +
                        "           hasNextPage\n" +
                        "           startCursor\n" +
                        "           endCursor\n" +
                        "       }\n" +
                        "   }\n" +
                        "}";
            }
        });
    }

    static class QueryResolver implements GraphQLQueryResolver {
        public Connection<User> users(int first, String after, DataFetchingEnvironment env) {
            return new SimpleListConnection<>(Collections.singletonList(new User(1L, "Luke"))).get(env);
        }
    }

    static class User {
        Long id;
        String name;

        public User(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }


}
