package com.coxautodev.graphql.tools;

import graphql.GraphQL;
import graphql.execution.AsyncExecutionStrategy;
import graphql.schema.GraphQLSchema;
import groovy.lang.Closure;
//import io.reactivex.Single;
//import io.reactivex.internal.operators.single.SingleJust;
import org.junit.Test;

import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

//import static io.reactivex.Maybe.just;

public class ReactiveTest {

    @Test
    public void futureSucceeds() {
        SchemaParserOptions options = SchemaParserOptions.newOptions()
//                .genericWrappers(
//                        new SchemaParserOptions.GenericWrapper(Single.class, 0),
//                        new SchemaParserOptions.GenericWrapper(SingleJust.class, 0)
//                )
                .build();
        GraphQLSchema schema = SchemaParser.newParser().file("Reactive.graphqls")
                .resolvers(new Query())
                .options(options)
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
//        Single<Optional<Organization>> organization(int organizationid) {
//            return Single.just(Optional.empty()); //CompletableFuture.completedFuture(null);
//        }

        Future<Optional<Organization>> organization(int organizationid) {
            return CompletableFuture.completedFuture(Optional.of(new Organization()));
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
