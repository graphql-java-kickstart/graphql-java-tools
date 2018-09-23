package com.coxautodev.graphql.tools


import spock.lang.Specification

class SuperclassResolverSpec extends Specification {

    def "methods from generic resolvers are resolved"() {
        when:
            SchemaParser.newParser().schemaString('''\
                        type Query {
                            bar: Bar!
                        }
                        
                        type Bar {
                            value: String
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
    }

    class BarResolver extends FooResolver<Bar> {

    }
}
