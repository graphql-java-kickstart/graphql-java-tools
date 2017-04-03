package com.coxautodev.graphql.tools

import spock.lang.Specification

/**
 * @author Andrew Potter
 */
class SchemaParserSpec extends Specification {

    SchemaParser.Builder builder

    def setup() {
        builder = SchemaParser.newParser()
            .schemaString('''
                type Query {
                    get(int: Int!): Int!
                }
            ''')
    }

    def "builder throws FileNotFound exception when file is missing"() {
        when:
            builder.file("/404")

        then:
            thrown(FileNotFoundException)
    }

    def "builder doesn't throw when file is present"() {
        when:
            builder.file("test.graphqls")

        then:
            noExceptionThrown()
    }

    def "parser throws SchemaError when Query resolver is missing"() {
        when:
            builder.build().makeExecutableSchema()

        then:
            thrown(SchemaError)
    }

    def "parser throws ResolverError when Query resolver is given without correct method"() {
        when:
            SchemaParser.newParser()
                .schemaString('''
                    schema {
                        query: QueryResolverMissing
                    }
                    type QueryResolverMissing {
                        get(int: Int!): Int!
                    }
                ''')
                .resolvers(new QueryResolverMissing())
                .build()
                .makeExecutableSchema()

        then:
            thrown(ResolverError)
    }

    def "should parse correctly when Query resolver is given"() {
        when:
            SchemaParser.newParser()
                .schemaString('''
                    schema {
                        query: QueryResolver
                    }
                    type QueryResolver {
                        get(int: Int!): Int!
                    }
                ''')
                .resolvers(new QueryResolver())
                .build()
                .makeExecutableSchema()

        then:
            noExceptionThrown()
    }
}

class QueryResolverMissing implements GraphQLRootResolver {

}

class QueryResolver implements GraphQLRootResolver {
    def int get(int i) { return i }
}
