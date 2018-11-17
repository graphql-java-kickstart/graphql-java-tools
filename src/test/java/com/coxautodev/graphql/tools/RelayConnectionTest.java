package com.coxautodev.graphql.tools;

import graphql.GraphQL;
import graphql.execution.AsyncExecutionStrategy;
import graphql.relay.Connection;
import graphql.relay.SimpleListConnection;
import graphql.schema.*;
import graphql.schema.idl.SchemaDirectiveWiring;
import graphql.schema.idl.SchemaDirectiveWiringEnvironment;
import groovy.lang.Closure;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class RelayConnectionTest {

    private static final Logger log = LoggerFactory.getLogger(RelayConnectionTest.class);

    @Test
    public void compiles() {
        SchemaParserOptions options = SchemaParserOptions.newOptions()

                .build();
        GraphQLSchema schema = SchemaParser.newParser().file("RelayConnection.graphqls")
                .resolvers(new QueryResolver())
                .dictionary(User.class)
                .directive("connection", new ConnectionDirective())
                .options(options)
                .build()
                .makeExecutableSchema();

        GraphQL gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(new AsyncExecutionStrategy())
                .build();

        Map<String,Object> variables = new HashMap<>();
        variables.put("limit", 10);
        Utils.assertNoGraphQlErrors(gql, variables, new Closure<String>(null) {
            @Override
            public String call() {
                return  "query {\n" +
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
                        "       }\n" +
                        "   }\n" +
                        "}";
            }
        });
    }

    static class QueryResolver implements GraphQLQueryResolver {
        // fixme #114: desired return type to use: Connection<User>
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

    static class ConnectionDirective implements SchemaDirectiveWiring {

        @Override
        public GraphQLFieldDefinition onField(SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition> environment) {
            GraphQLFieldDefinition field = environment.getElement();
            log.info("Transforming field");
            return field;
        }

    }


}
