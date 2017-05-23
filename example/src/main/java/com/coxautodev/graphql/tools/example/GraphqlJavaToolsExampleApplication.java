package com.coxautodev.graphql.tools.example;

import com.coxautodev.graphql.tools.example.types.Droid;
import com.coxautodev.graphql.tools.example.types.Episode;
import com.coxautodev.graphql.tools.example.types.Human;
import com.oembedler.moon.graphql.boot.SchemaParserDictionary;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GraphqlJavaToolsExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraphqlJavaToolsExampleApplication.class, args);
    }

    @Bean
    public SchemaParserDictionary schemaParserDictionary() {
        return new SchemaParserDictionary()
            .dictionary(Human.class, Droid.class, Episode.class);
    }
}
