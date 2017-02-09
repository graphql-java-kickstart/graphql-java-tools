package com.coxautodev.graphql.tools.example;

import com.coxautodev.graphql.tools.GraphQLResolver;
import com.coxautodev.graphql.tools.SchemaParser;
import com.coxautodev.graphql.tools.example.types.Droid;
import com.coxautodev.graphql.tools.example.types.Episode;
import com.coxautodev.graphql.tools.example.types.Human;
import graphql.schema.GraphQLSchema;
import graphql.servlet.GraphQLController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
public class GraphqlJavaToolsExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraphqlJavaToolsExampleApplication.class, args);
    }

    @Autowired
    private List<GraphQLResolver> resolvers;

    @Bean
    public GraphQLSchema graphQLSchema() {
        return SchemaParser.newParser()
            .file("swapi.graphqls")
            .resolvers(resolvers)
            .dataClasses(Human.class, Droid.class)
            .enums(Episode.class)
            .build()
            .makeExecutableSchema();
    }

    @Bean
    GraphQLController graphQLController() {
        return new GraphQLController();
    }
}
