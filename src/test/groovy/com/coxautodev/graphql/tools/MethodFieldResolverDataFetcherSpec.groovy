package com.coxautodev.graphql.tools

import graphql.Assert
import graphql.ExecutionResult
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionContextBuilder
import graphql.execution.ExecutionId
import graphql.execution.ExecutionStrategy
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.instrumentation.SimpleInstrumentation
import graphql.language.BooleanValue
import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.NonNullType
import graphql.language.TypeName
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingEnvironmentBuilder
import graphql.schema.DataFetchingEnvironmentImpl
import spock.lang.Specification

import java.util.concurrent.CompletableFuture

/**
 * @author Andrew Potter
 */
class MethodFieldResolverDataFetcherSpec extends Specification {

    def "data fetcher throws exception if resolver has too many arguments"() {
        when:
            createFetcher("active", new GraphQLQueryResolver() {
                boolean active(def arg1, def arg2) { true }
            })

        then:
            thrown(FieldResolverError)
    }

    def "data fetcher throws exception if resolver has too few arguments"() {
        when:
            createFetcher("active", [new InputValueDefinition("doesNotExist", new TypeName("Boolean"))], new GraphQLQueryResolver() {
                boolean active() { true }
            })

        then:
            thrown(FieldResolverError)
    }

    def "data fetcher prioritizes methods on the resolver"() {
        setup:
            def name = "Resolver Name"
            def resolver = createFetcher("name", new GraphQLResolver<DataClass>() {
                String getName(DataClass dataClass) { name }
            })

        expect:
            resolver.get(createEnvironment(new DataClass())) == name
    }

    def "data fetcher uses data class methods if no resolver method is given"() {
        setup:
            def resolver = createFetcher("name", new GraphQLResolver<DataClass>() {})

        expect:
            resolver.get(createEnvironment(new DataClass())) == DataClass.name
    }

    def "data fetcher prioritizes methods without a prefix"() {
        setup:
            def name = "correct name"
            def resolver = createFetcher("name", new GraphQLResolver<DataClass>() {
                String name(DataClass dataClass) { name }

                String getName(DataClass dataClass) { "in" + name }
            })

        expect:
            resolver.get(createEnvironment(new DataClass())) == name
    }

    def "data fetcher uses 'is' prefix for booleans (primitive type)"() {
        setup:
            def resolver = createFetcher("active", new GraphQLResolver<DataClass>() {
                boolean isActive(DataClass dataClass) { true }

                boolean getActive(DataClass dataClass) { true }
            })

        expect:
            resolver.get(createEnvironment(new DataClass()))
    }

    def "data fetcher uses 'is' prefix for Booleans (Object type)"() {
        setup:
            def resolver = createFetcher("active", new GraphQLResolver<DataClass>() {
                Boolean isActive(DataClass dataClass) { Boolean.TRUE }

                Boolean getActive(DataClass dataClass) { Boolean.TRUE }
            })

        expect:
            resolver.get(createEnvironment(new DataClass()))
    }

    def "data fetcher passes environment if method has extra argument"() {
        setup:
            def resolver = createFetcher("active", new GraphQLResolver<DataClass>() {
                boolean isActive(DataClass dataClass, DataFetchingEnvironment env) {
                    env instanceof DataFetchingEnvironment
                }
            })

        expect:
            resolver.get(createEnvironment(new DataClass()))
    }

    def "data fetcher passes environment if method has extra argument even if context is specified"() {
        setup:
            def options = SchemaParserOptions.newOptions().contextClass(ContextClass).build()
            def resolver = createFetcher(options, "active", new GraphQLResolver<DataClass>() {
                boolean isActive(DataClass dataClass, DataFetchingEnvironment env) {
                    env instanceof DataFetchingEnvironment
                }
            })

        expect:
            resolver.get(createEnvironment(new ContextClass(), new DataClass()))
    }

    def "data fetcher passes context if method has extra argument and context is specified"() {
        setup:
            def context = new ContextClass()
            def options = SchemaParserOptions.newOptions().contextClass(ContextClass).build()
            def resolver = createFetcher(options, "active", new GraphQLResolver<DataClass>() {
                boolean isActive(DataClass dataClass, ContextClass ctx) {
                    ctx == context
                }
            })

        expect:
            resolver.get(createEnvironment(context, new DataClass()))
    }

    def "data fetcher marshalls input object if required"() {
        setup:
            def name = "correct name"
            def resolver = createFetcher("active", [new InputValueDefinition("input", new TypeName("InputClass"))], new GraphQLQueryResolver() {
                boolean active(InputClass input) {
                    input instanceof InputClass && input.name == name
                }
            })

        expect:
            resolver.get(createEnvironment([input: [name: name]]))
    }

    def "data fetcher doesn't marshall input object if not required"() {
        setup:
            def name = "correct name"
            def resolver = createFetcher("active", [new InputValueDefinition("input", new TypeName("Map"))], new GraphQLQueryResolver() {
                boolean active(Map input) {
                    input instanceof Map && input.name == name
                }
            })

        expect:
            resolver.get(createEnvironment([input: [name: name]]))
    }

    def "data fetcher returns null if nullable argument is passed null"() {
        setup:
            def resolver = createFetcher("echo", [new InputValueDefinition("message", new TypeName("String"))], new GraphQLQueryResolver() {
                String echo(String message) {
                    return message
                }
            })

        expect:
            resolver.get(createEnvironment()) == null
    }

    def "data fetcher throws exception if non-null argument is passed null"() {
        setup:
            def resolver = createFetcher("echo", [new InputValueDefinition("message", new NonNullType(new TypeName("String")))], new GraphQLQueryResolver() {
                String echo(String message) {
                    return message
                }
            })

        when:
            resolver.get(createEnvironment())

        then:
            thrown(ResolverError)
    }

    private static DataFetcher createFetcher(String methodName, List<InputValueDefinition> arguments = [], GraphQLResolver<?> resolver) {
        return createFetcher(SchemaParserOptions.defaultOptions(), methodName, arguments, resolver)
    }

    private static DataFetcher createFetcher(SchemaParserOptions options, String methodName, List<InputValueDefinition> arguments = [], GraphQLResolver<?> resolver) {
        def field = new FieldDefinition(methodName, new TypeName('Boolean')).with { getInputValueDefinitions().addAll(arguments); it }

        new FieldResolverScanner(options).findFieldResolver(field, resolver instanceof GraphQLQueryResolver ? new RootResolverInfo([resolver], options) : new NormalResolverInfo(resolver, options)).createDataFetcher()
    }

    private static DataFetchingEnvironment createEnvironment(Map<String, Object> arguments = [:]) {
        createEnvironment(new Object(), arguments)
    }

    private static DataFetchingEnvironment createEnvironment(Object source, Map<String, Object> arguments = [:]) {
        createEnvironment(null, source, arguments)
    }

    private static DataFetchingEnvironment createEnvironment(Object context, Object source, Map<String, Object> arguments = [:]) {
        DataFetchingEnvironmentBuilder.newDataFetchingEnvironment()
                .source(source)
                .arguments(arguments)
                .context(context)
                .executionContext(buildExecutionContext())
                .build()
    }

    private static ExecutionContext buildExecutionContext() {
        ExecutionStrategy executionStrategy = new ExecutionStrategy() {
            @Override
            CompletableFuture<ExecutionResult> execute(ExecutionContext executionContext, ExecutionStrategyParameters parameters) {
                return Assert.assertShouldNeverHappen("should not be called")
            }
        }
        ExecutionId executionId = ExecutionId.from("executionId123")
        ExecutionContextBuilder.newExecutionContextBuilder()
            .instrumentation(SimpleInstrumentation.INSTANCE)
            .executionId(executionId)
            .queryStrategy(executionStrategy)
            .mutationStrategy(executionStrategy)
            .subscriptionStrategy(executionStrategy)
            .build()
    }

    class DataClass {
        private static final String name = "Data Class Name"

        String getName() {
            name
        }
    }

    static class InputClass {
        String name
    }

    class ContextClass {
    }
}
