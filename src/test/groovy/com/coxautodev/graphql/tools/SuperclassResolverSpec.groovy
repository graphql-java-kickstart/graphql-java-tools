package com.coxautodev.graphql.tools


import spock.lang.Specification

class SuperclassResolverSpec extends Specification {

    def "methods from generic resolvers are resolved"() {
        when:
            SchemaParser.newParser().schemaString('''\
                        type Query {
                            bar: Bar!
                        }
                        
                        type Bar implements Foo{
                            value: String
                            getValueWithSeveralParameters(arg1: Boolean!, arg2: String): String!
                        }
                        
                        interface Foo {
                            getValueWithSeveralParameters(arg1: Boolean!, arg2: String): String!
                        }
                        ''')
                    .resolvers(new QueryResolver(), new BarResolver())
                    .build()
                    .makeExecutableSchema()

        then:
            noExceptionThrown()
    }

    class QueryResolver implements GraphQLQueryResolver {
        Bar getBar() {
            return new Bar()
        }
    }

    class Bar {
    }

    abstract class FooResolver<T> implements GraphQLResolver<Bar> {
        String getValue(T foo) {
            return "value"
        }

        String getValueWithSeveralParameters(T foo, boolean arg1, String arg2) {
            if (arg1) {
                return "value"
            } else {
                return arg2
            }
        }
    }

    class BarResolver extends FooResolver<Bar> {

    }
}
