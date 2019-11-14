package com.coxautodev.graphql.tools


import spock.lang.Specification

class GenericResolverSpec extends Specification {

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
                    .resolvers(new QueryResolver1(), new BarResolver())
                    .build()
                    .makeExecutableSchema()

        then:
            noExceptionThrown()
    }

    class QueryResolver1 implements GraphQLQueryResolver {
        Bar getBar() {
            return new Bar()
        }
    }

    class Bar {
    }

    abstract class FooResolver<T> implements GraphQLResolver<T> {
        String getValue(T foo) {
            return "value"
        }
    }

    class BarResolver extends FooResolver<Bar> implements GraphQLResolver<Bar> {

    }


    def "methods from generic inherited resolvers are resolved"() {
        when:
        SchemaParser.newParser().schemaString('''\
                        type Query {
                            car: Car!
                        }
                        type Car {
                            value: String
                        }
                        ''')
                .resolvers(new QueryResolver2(), new CarResolver())
                .build()
                .makeExecutableSchema()

        then:
        noExceptionThrown()
    }


    class QueryResolver2 implements GraphQLQueryResolver {
        Car getCar() {
            return new Car()
        }
    }

    abstract class FooGraphQLResolver<T> implements GraphQLResolver<T> {
        String getValue(T foo) {
            return "value"
        }
    }

    class Car {
    }

    class CarResolver extends FooGraphQLResolver<Car> {

    }
}
