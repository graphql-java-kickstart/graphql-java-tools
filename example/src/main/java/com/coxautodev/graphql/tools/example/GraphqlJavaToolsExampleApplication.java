package com.coxautodev.graphql.tools.example;

import com.coxautodev.graphql.tools.GraphQLResolver;
import com.coxautodev.graphql.tools.SchemaParser;
import com.coxautodev.graphql.tools.example.types.Droid;
import com.coxautodev.graphql.tools.example.types.Episode;
import com.coxautodev.graphql.tools.example.types.Human;
import graphql.execution.SimpleExecutionStrategy;
import graphql.schema.GraphQLSchema;
import graphql.servlet.SimpleGraphQLServlet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
public class GraphqlJavaToolsExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraphqlJavaToolsExampleApplication.class, args);
    }

    @Autowired
    private List<GraphQLResolver<?>> resolvers;

    @Bean
    public GraphQLSchema graphQLSchema() {
        return SchemaParser.newParser()
            .file("swapi.graphqls")
            .resolvers(resolvers)
            .dictionary(Human.class, Droid.class, Episode.class)
            .build()
            .makeExecutableSchema();
    }

    @Bean
    ServletRegistrationBean graphQLServlet(GraphQLSchema graphQLSchema) {
        return new ServletRegistrationBean(new SimpleGraphQLServlet(graphQLSchema, new SimpleExecutionStrategy()), "/graphql/*");
    }
}
