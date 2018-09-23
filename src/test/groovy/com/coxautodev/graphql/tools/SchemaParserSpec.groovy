package com.coxautodev.graphql.tools

import graphql.language.SourceLocation
import graphql.schema.GraphQLSchema
import org.springframework.aop.framework.ProxyFactory
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
            builder.file("/404").build()

        then:
            thrown(FileNotFoundException)
    }

    def "builder doesn't throw FileNotFound exception when file is present"() {
        when:
            SchemaParser.newParser().file("test.graphqls")
                    .resolvers(new GraphQLQueryResolver() {
                String getId() { "1" }
            })
                    .build()

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
                    .resolvers(new GraphQLQueryResolver() {})
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

    def "parser should parse correctly when multiple query resolvers are given"() {
        when:
            SchemaParser.newParser()
                    .schemaString('''
                    type Obj {
                        name: String
                    }

                    type AnotherObj {
                        key: String
                    }

                    type Query {
                        obj: Obj
                        anotherObj: AnotherObj
                    }
                ''')
                    .resolvers(new GraphQLQueryResolver() {
                Obj getObj() { return new Obj() }
            }, new GraphQLQueryResolver() {
                AnotherObj getAnotherObj() { return new AnotherObj() }
            })
                    .build()
                    .makeExecutableSchema()

        then:
            noExceptionThrown()
    }

    def "parser should parse correctly when multiple resolvers for the same data type are given"() {
        when:
            SchemaParser.newParser()
                    .schemaString('''
                    type RootObj {
                        obj: Obj
                        anotherObj: AnotherObj
                    }
                    
                    type Obj {
                        name: String
                    }
                    
                    type AnotherObj {
                        key: String
                    }
                    
                    type Query {
                        rootObj: RootObj
                    }
                ''')
                    .resolvers(new GraphQLQueryResolver() {
                RootObj getRootObj() { return new RootObj() }
            }, new GraphQLResolver<RootObj>() {
                Obj getObj(RootObj rootObj) { return new Obj() }
            }, new GraphQLResolver<RootObj>() {
                AnotherObj getAnotherObj(RootObj rootObj) { return new AnotherObj() }
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

    def "parser handles enums with overridden toString method"() {
        when:
            SchemaParser.newParser()
                    .schemaString('''
                    enum CustomEnum {
                        FOO
                    }
                    
                    type Query {
                        customEnum: CustomEnum
                    }
                ''')
                    .resolvers(new GraphQLQueryResolver() {
                CustomEnum customEnum() { null }
            })
                    .build()
                    .makeExecutableSchema()
        then:
            noExceptionThrown()
    }

    def "parser should include source location for field definition"() {
        when:
            GraphQLSchema schema = SchemaParser.newParser()
                    .schemaString('''\
                                type Query {
                                    id: ID!
                                }
                            '''.stripIndent())
                    .resolvers(new QueryWithIdResolver())
                    .build()
                    .makeExecutableSchema()

        then:
            SourceLocation sourceLocation = schema.getObjectType("Query")
                    .getFieldDefinition("id")
                    .definition.sourceLocation
            sourceLocation != null
            sourceLocation.line == 2
            sourceLocation.column == 5
            sourceLocation.sourceName == null
    }

    def "parser should include source location for field definition when loaded from single classpath file"() {
        when:
            GraphQLSchema schema = SchemaParser.newParser()
                    .file("test.graphqls")
                    .resolvers(new QueryWithIdResolver())
                    .build()
                    .makeExecutableSchema()

        then:
            SourceLocation sourceLocation = schema.getObjectType("Query")
                    .getFieldDefinition("id")
                    .definition.sourceLocation
            sourceLocation != null
            sourceLocation.line == 2
            sourceLocation.column == 5
            sourceLocation.sourceName == "test.graphqls"
    }

    def "support enum types if only used as input type"() {
        when:
            SchemaParser.newParser().schemaString('''\
                    type Query { test: Boolean }
                    
                    type Mutation {
                        save(input: SaveInput!): Boolean
                    }
                    
                    input SaveInput {
                        type: EnumType!
                    }
                    
                    enum EnumType {
                        TEST
                    }
                '''.stripIndent())
                    .resolvers(new GraphQLMutationResolver() {
                boolean save(SaveInput input) { false }

                class SaveInput {
                    EnumType type;
                }

            }, new GraphQLQueryResolver() {
                boolean test() { false }
            })
                    .dictionary(EnumType.class)
                    .build()
                    .makeExecutableSchema()

        then:
            noExceptionThrown()
    }

    def "support enum types if only used in input Map"() {
        when:
            SchemaParser.newParser().schemaString('''\
                    type Query { test: Boolean }
                    
                    type Mutation {
                        save(input: SaveInput!): Boolean
                    }
                    
                    input SaveInput {
                        age: Int
                        type: EnumType!
                    }
                    
                    enum EnumType {
                        TEST
                    }
                '''.stripIndent())
                    .resolvers(new GraphQLMutationResolver() {
                boolean save(Map input) { false }
            }, new GraphQLQueryResolver() {
                boolean test() { false }
            })
                    .dictionary(EnumType.class)
                    .build()
                    .makeExecutableSchema()

        then:
            noExceptionThrown()
    }

    enum EnumType { TEST }

    class QueryWithIdResolver implements GraphQLQueryResolver {
        String getId() { null }
    }

    class Filter {
        String filter() { null }
    }

    class CustomGenericWrapper<T, V> {}

    class Obj {
        def name() { null }
    }

    class AnotherObj {
        def key() { null }
    }

    class RootObj {
    }

    class ProxiedResolver implements GraphQLQueryResolver {
        List<String> test() { [] }
    }

    enum CustomEnum {
        FOO{
            @Override
            String toString() {
                return "Bar"
            }
        }
    }

}
