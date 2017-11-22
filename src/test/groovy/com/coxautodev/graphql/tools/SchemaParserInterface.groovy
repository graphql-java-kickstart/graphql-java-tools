package com.coxautodev.graphql.tools

import graphql.GraphQL
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

/**
 * @author Andrew Potter
 */
class SchemaParserInterface extends Specification {

    def "should parse interface with complex type"() {
        when:
            SchemaParser.newParser()
                .schemaString(''' 
                    schema {
                        query: Query
                    }
                    
                    type Query {
                        animal: Animal
                    }
                    
                    interface Animal {
                        type: ComplexType
                    }
                    
                    type Dog implements Animal {
                        type: ComplexType
                    }
                    
                    type ComplexType {
                        id: String
                    }
                ''')
                .resolvers(new QueryResolver())
                .build()
                .makeExecutableSchema()

        then:
            noExceptionThrown()

    }

    class QueryResolver implements GraphQLQueryResolver {
        Animal animal(){}
    }
    interface Animal {
        ComplexType type
    }
    class Dog implements Animal {
        
    }
    class ComplexType {
        String id
    }
}
