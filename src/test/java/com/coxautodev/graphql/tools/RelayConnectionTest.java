package com.coxautodev.graphql.tools;

import graphql.relay.Connection;
import graphql.relay.SimpleListConnection;
import graphql.schema.*;
import graphql.schema.idl.SchemaDirectiveWiring;
import graphql.schema.idl.SchemaDirectiveWiringEnvironment;
import graphql.schema.idl.SchemaDirectiveWiringEnvironmentImpl;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

public class RelayConnectionTest {

    private static final Logger log = LoggerFactory.getLogger(RelayConnectionTest.class);

    @Test
    public void compiles() {
        SchemaParser.newParser().file("RelayConnection.graphqls")
                .resolvers(new QueryResolver())
                .dictionary(User.class)
                .directive("connection", new RelayConnection())
                .directive("uppercase", new UppercaseDirective())
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

    static class RelayConnection implements SchemaDirectiveWiring {

        @Override
        public GraphQLFieldDefinition onField(SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition> environment) {
            GraphQLFieldDefinition field = environment.getElement();
            log.info("Transforming field");
            return field;
        }

    }

    static class UppercaseDirective implements SchemaDirectiveWiring {

        @Override
        public GraphQLFieldDefinition onField(SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition> env) {
            GraphQLFieldDefinition field = env.getElement();
            DataFetcher dataFetcher = DataFetcherFactories.wrapDataFetcher(field.getDataFetcher(), ((dataFetchingEnvironment, value) -> {
                if (value == null) {
                    return null;
                }
                String uppercase = ((String) value).toUpperCase();
                return uppercase;
            }));
            return field.transform(builder -> builder.dataFetcher(dataFetcher));
        }
    }
}
