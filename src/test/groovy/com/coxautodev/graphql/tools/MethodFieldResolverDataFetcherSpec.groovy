package com.coxautodev.graphql.tools

import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.NonNullType
import graphql.language.TypeName
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingEnvironmentImpl
import spock.lang.Specification

/**
 * @author Andrew Potter
 */
class MethodFieldResolverDataFetcherSpec extends Specification {

    static final FieldResolverScanner fieldResolverScanner = new FieldResolverScanner()

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
            createFetcher("active", [new InputValueDefinition("doesNotExist")], new GraphQLQueryResolver() {
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

    def "data fetcher marshalls input object if required"() {
        setup:
            def name = "correct name"
            def resolver = createFetcher("active", [new InputValueDefinition("input")], new GraphQLQueryResolver() {
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
            def resolver = createFetcher("active", [new InputValueDefinition("input")], new GraphQLQueryResolver() {
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
        def field = new FieldDefinition(methodName, new TypeName('Boolean')).with { getInputValueDefinitions().addAll(arguments); it }

        fieldResolverScanner.findFieldResolver(field, resolver instanceof GraphQLQueryResolver ? new RootResolverInfo([resolver]) : new NormalResolverInfo(resolver)).createDataFetcher()
    }

    private static DataFetchingEnvironment createEnvironment(Map<String, Object> arguments = [:]) {
        createEnvironment(new Object(), arguments)
    }

    private static DataFetchingEnvironment createEnvironment(Object source, Map<String, Object> arguments = [:]) {
        new DataFetchingEnvironmentImpl(source, arguments, null, null, null, null, null, null, null, null)
    }
}

class DataClass {
    private static final String name = "Data Class Name"

    String getName() {
        name
    }
}

class InputClass {
    String name
}
