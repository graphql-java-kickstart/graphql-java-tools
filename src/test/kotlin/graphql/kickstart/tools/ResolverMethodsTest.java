package graphql.kickstart.tools;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ResolverMethodsTest {

    // Note: don't convert this code to Kotlin, since it's quite important that the
    //       resolver method is defined with an argument of primitive type, like 'boolean', not 'Boolean':
    //         String testOmittedBoolean(boolean value1, Boolean value2)
    @Test
    public void testOmittedBooleanArgument() {
        // In this schema, the 'value1' argument is optional, but the Java resolver defines it as 'boolean'
        // Instead of failing with an error, we expect the argument to be set to the Java default (i.e. false for booleans)
        GraphQLSchema schema = SchemaParser.newParser()
            .schemaString("type Query { testOmittedBoolean(value1: Boolean, value2: Boolean): String }")
            .resolvers(new Resolver())
            .build()
            .makeExecutableSchema();

        GraphQL gql = GraphQL.newGraphQL(schema).build();

        ExecutionResult result = gql
            .execute(ExecutionInput.newExecutionInput()
                .query("query { testOmittedBoolean }")
                .root(new Object()));

        assertTrue(result.getErrors().isEmpty());
        assertEquals("false,null", ((Map<?, ?>) result.getData()).get("testOmittedBoolean"));
    }

    // Kotlin reflection used to fail on anonymous Java classes (KT-41373), so these resolvers must stay in Java.
    @Test
    public void testAnonymousClassResolvers() {
        GraphQLSchema schema = SchemaParser.newParser()
            .schemaString("type Query { hello(name: String!): String! product: Product! } type Product { name: String! }")
            .resolvers(
                new GraphQLQueryResolver() {
                    @SuppressWarnings("unused")
                    public String hello(String name) {
                        return "Hello, " + name;
                    }

                    @SuppressWarnings("unused")
                    public Product product() {
                        return new Product();
                    }
                },
                new GraphQLResolver<Product>() {
                    @SuppressWarnings("unused")
                    public String name(Product product) {
                        return "product";
                    }
                })
            .build()
            .makeExecutableSchema();

        GraphQL gql = GraphQL.newGraphQL(schema).build();

        ExecutionResult result = gql
            .execute(ExecutionInput.newExecutionInput()
                .query("query { hello(name: \"World\") product { name } }")
                .root(new Object()));

        assertTrue(result.getErrors().isEmpty());
        Map<?, ?> data = result.getData();
        assertEquals("Hello, World", data.get("hello"));
        assertEquals(Map.of("name", "product"), data.get("product"));
    }

    // Raw types can't be expressed in Kotlin, so this resolver must stay in Java.
    @Test
    public void testRawResolverForParameterizedDataClass() {
        GraphQLSchema schema = SchemaParser.newParser()
            .schemaString("type Query { page: ItemPage! } type ItemPage { content: [Item!]! size: Int! } type Item { name: String! }")
            .resolvers(new PageQueryResolver(), new RawPageResolver())
            .build()
            .makeExecutableSchema();

        GraphQL gql = GraphQL.newGraphQL(schema).build();

        ExecutionResult result = gql
            .execute(ExecutionInput.newExecutionInput()
                .query("query { page { content { name } size } }")
                .root(new Object()));

        assertTrue(result.getErrors().isEmpty());
        Map<?, ?> data = result.getData();
        assertEquals(Map.of("content", List.of(Map.of("name", "item")), "size", 1), data.get("page"));
    }

    static class Product {
    }

    static class Page<T> {
        private final List<T> content;

        Page(List<T> content) {
            this.content = content;
        }

        public List<T> getContent() {
            return content;
        }
    }

    static class Item {
        public String getName() {
            return "item";
        }
    }

    static class PageQueryResolver implements GraphQLQueryResolver {

        @SuppressWarnings("unused")
        public Page<Item> page() {
            return new Page<>(List.of(new Item()));
        }
    }

    @SuppressWarnings("rawtypes")
    static class RawPageResolver implements GraphQLResolver<Page> {

        @SuppressWarnings("unused")
        public int size(Page page) {
            return page.getContent().size();
        }
    }

    static class Resolver implements GraphQLQueryResolver {

        @SuppressWarnings("unused")
        public String testOmittedBoolean(boolean value1, Boolean value2) {
            return value1 + "," + value2;
        }
    }
}
