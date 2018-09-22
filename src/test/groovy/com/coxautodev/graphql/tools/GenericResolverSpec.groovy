package com.coxautodev.graphql.tools

import graphql.schema.GraphQLSchema
import spock.lang.Specification

class GenericResolverSpec extends Specification {

    def "methods from generic resolvers are resolved"() {
        when:
            GraphQLSchema schema = SchemaParser.newParser().schemaString('''\
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
            schema != null
    }

}

class QueryResolver implements GraphQLQueryResolver {
    Bar getBar() {
        return new Bar()
    }
}

class Bar {
}

abstract class FooResolver<T> {
    String getValue(T foo) {
        return "value"
    }
}

class BarResolver extends FooResolver<Bar> implements GraphQLResolver<Bar> {

}
