package com.coxautodev.graphql.tools

import org.springframework.aop.framework.AopProxyUtils
import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.support.AopUtils
import spock.lang.Specification

import java.util.concurrent.Future

/**
 * @author Andrew Potter
 */
class SchemaParserSpec extends Specification {

    SchemaParserBuilder builder

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
            thrown(SchemaClassScannerError)
    }

    def "parser throws ResolverError when Query resolver is given without correct method"() {
        when:
            SchemaParser.newParser()
                .schemaString('''
                    type Query {
                        get(int: Int!): Int!
                    }
                ''')
                .resolvers(new GraphQLQueryResolver() { })
                .build()
                .makeExecutableSchema()

        then:
            thrown(FieldResolverError)
    }

    def "parser should parse correctly when Query resolver is given"() {
        when:
            SchemaParser.newParser()
                .schemaString('''
                    type Query {
                        get(int: Int!): Int!
                    }
                ''')
                .resolvers(new GraphQLQueryResolver() {
                    int get(int i) { return i }
                })
                .build()
                .makeExecutableSchema()

        then:
            noExceptionThrown()
    }

    def "parser should allow setting custom generic wrappers"() {
        when:
            SchemaParser.newParser()
                .schemaString('''
                    type Query {
                        one: Object!
                        two: Object!
                    }
                    
                    type Object {
                        name: String!
                    }
                ''')
                .resolvers(new GraphQLQueryResolver() {
                    CustomGenericWrapper<Integer, Obj> one() { null }
                    Obj two() { null }
                })
                .options(SchemaParserOptions.newOptions().genericWrappers(new SchemaParserOptions.GenericWrapper(CustomGenericWrapper, 1)).build())
                .build()
                .makeExecutableSchema()

        then:
            noExceptionThrown()
    }

    def "parser should allow turning off default generic wrappers"() {
        when:
            SchemaParser.newParser()
                .schemaString('''
                    type Query {
                        one: Object!
                        two: Object!
                    }
                    
                    type Object {
                        toString: String!
                    }
                ''')
                .resolvers(new GraphQLQueryResolver() {
                    Future<Obj> one() { null }
                    Obj two() { null }
                })
                .options(SchemaParserOptions.newOptions().useDefaultGenericWrappers(false).build())
                .build()
                .makeExecutableSchema()

        then:
            thrown(TypeClassMatcher.RawClassRequiredForGraphQLMappingException)
    }

    def "parser should throw descriptive exception when object is used as input type incorrectly"() {
        when:
            SchemaParser.newParser()
                .schemaString('''
                    type Query {
                        name(filter: Filter): [String]
                    }
                    
                    type Filter {
                        filter: String
                    }
                ''')
                .resolvers(new GraphQLQueryResolver() {
                    List<String> name(Filter filter) { null }
                })
                .build()
                .makeExecutableSchema()

        then:
            def t = thrown(SchemaError)
            t.message.contains("Was a type only permitted for object types incorrectly used as an input type, or vice-versa")
    }

    def "parser handles spring AOP proxied resolvers by default"() {
        when:
            def resolver = new ProxyFactory(new ProxiedResolver()).getProxy() as GraphQLQueryResolver

            SchemaParser.newParser()
                .schemaString('''
                    type Query {
                        test: [String]
                    }
                ''')
                .resolvers(resolver)
                .build()
        then:
            noExceptionThrown()
    }
}

class Filter {
    String filter() { null }
}
class CustomGenericWrapper<T, V> { }
class Obj {
    def name() { null }
}

class ProxiedResolver implements GraphQLQueryResolver {
    List<String> test() { [] }
}
