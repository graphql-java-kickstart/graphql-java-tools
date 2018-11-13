package com.coxautodev.graphql.tools;

import graphql.GraphQL;
import graphql.execution.AsyncExecutionStrategy;
import graphql.schema.GraphQLSchema;
import groovy.lang.Closure;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.Future;

public class ReactiveTest {

    @Test
    public void futureSucceeds() {
        GraphQLSchema schema = SchemaParser.newParser().file("Reactive.graphqls")
                .resolvers(new Query())
                .build()
                .makeExecutableSchema();

        GraphQL gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(new AsyncExecutionStrategy())
                .build();
        Utils.assertNoGraphQlErrors(gql, new HashMap<>(), new Object(), new Closure<String>(null) {
            @Override
            public String call() {
                return "query { organization(organizationId: 1) { user { id } } }";
            }
        });
    }

    static class Query implements GraphQLQueryResolver {
        Future<Organization> organization(int organizationid) {
            return null;
        }
    }

    static class Organization {
        private User user;
    }

    static class User {
        private Long id;
        private String name;
    }
}
