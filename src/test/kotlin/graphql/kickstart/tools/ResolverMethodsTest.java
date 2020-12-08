package graphql.kickstart.tools;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ResolverMethodsTest {
    // Note: don't convert this code to Kotlin or Groovy, since it's quite important that the
    //       resolver method is defined with an argument of primitive type, like 'boolean', not 'Boolean':
    //         String testOmittedBoolean(boolean value1, Boolean value2)
    @Test
    public void testOmittedBooleanArgument() {
        // In this schema, the 'value1' argument is optional, but the Java resolver defines it as 'boolean'
        // Instead of failing with an error, we expect the argument to be set to the Java default (i.e. false for booleans)
        GraphQLSchema schema = SchemaParser.newParser()
                .schemaString("" +
                        "type Query {" +
                        "   testOmittedBoolean(value1: Boolean, value2: Boolean): String" +
                        "}")
                .resolvers(new Resolver())
                .build()
                .makeExecutableSchema();

        GraphQL gql = GraphQL.newGraphQL(schema).build();

        ExecutionResult result = gql
                .execute(ExecutionInput.newExecutionInput()
                        .query("" +
                                "query { " +
                                "  testOmittedBoolean" +
                                "}")
                        .context(new Object())
                        .root(new Object()));

        assertTrue(result.getErrors().isEmpty());
        assertEquals("false,null", ((Map<?, ?>) result.getData()).get("testOmittedBoolean"));
    }

    static class Resolver implements GraphQLQueryResolver {
        @SuppressWarnings("unused")
        public String testOmittedBoolean(boolean value1, Boolean value2) {
            return value1 + "," + value2;
        }
    }
}
