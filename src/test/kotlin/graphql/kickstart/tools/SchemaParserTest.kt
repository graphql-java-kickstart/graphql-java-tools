package graphql.kickstart.tools

import graphql.ExecutionResult
import graphql.GraphQL
import graphql.kickstart.tools.resolver.FieldResolverError
import graphql.parser.InvalidSyntaxException
import graphql.schema.*
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.reactive.publish
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.reactivestreams.Publisher
import org.reactivestreams.tck.TestEnvironment
import org.springframework.aop.framework.ProxyFactory
import java.io.FileNotFoundException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.CompletableFuture.completedStage
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future

class SchemaParserTest {
    private lateinit var builder: SchemaParserBuilder

    @Before
    fun setup() {
        builder = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    get(int: Int!): Int!
                }
                """)
    }

    @Test(expected = FileNotFoundException::class)
    fun `builder throws FileNotFound exception when file is missing`() {
        builder.file("/404").build()
    }

    @Test
    fun `builder doesn't throw FileNotFound exception when file is present`() {
        val schema = SchemaParser.newParser().file("Test.graphqls")
            .resolvers(object : GraphQLQueryResolver {
                fun getId(): String = "1"
            })
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
    }

    @Test(expected = SchemaClassScannerError::class)
    fun `parser throws SchemaError when Query resolver is missing`() {
        builder.build().makeExecutableSchema()
    }

    @Test(expected = FieldResolverError::class)
    fun `parser throws ResolverError when Query resolver is given without correct method`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    get(int: Int!): Int!
                }
                """)
            .resolvers(object : GraphQLQueryResolver {})
            .build()
            .makeExecutableSchema()
    }

    @Test
    fun `parser should parse correctly when Query resolver is given`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    get(int: Int!): Int!
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun get(i: Int): Int = i
            })
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
    }

    @Test
    fun `parser should parse correctly when multiple query resolvers are given`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
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
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun getObj(): Obj = Obj()
            }, object : GraphQLQueryResolver {
                fun getAnotherObj(): AnotherObj = AnotherObj()
            })
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
    }

    @Test
    fun `parser should parse correctly when multiple resolvers for the same data type are given`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
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
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun getRootObj(): RootObj {
                    return RootObj()
                }
            }, object : GraphQLResolver<RootObj> {
                fun getObj(rootObj: RootObj): Obj {
                    return Obj()
                }
            }, object : GraphQLResolver<RootObj> {
                fun getAnotherObj(rootObj: RootObj): AnotherObj {
                    return AnotherObj()
                }
            })
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
    }

    @Test
    fun `parser should allow setting custom generic wrappers`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    one: Object!
                    two: Object!
                }

                type Object {
                    name: String!
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun one(): CustomGenericWrapper<Int, Obj> = CustomGenericWrapper(Obj())
                fun two(): Obj = Obj()
            })
            .options(SchemaParserOptions.newOptions().genericWrappers(SchemaParserOptions.GenericWrapper.withTransformer(CustomGenericWrapper::class, 1, { wrapper: CustomGenericWrapper<*, *> -> wrapper.value })).build())
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
    }

    @Test(expected = SchemaClassScannerError::class)
    fun `parser should allow turning off default generic wrappers`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    one: Object!
                    two: Object!
                }
                
                type Object {
                    toString: String!
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun one(): Future<Obj>? = null
                fun two(): Obj? = null
            })
            .options(SchemaParserOptions.newOptions().useDefaultGenericWrappers(false).build())
            .build()
            .makeExecutableSchema()
    }

    @Test
    fun `parser should throw descriptive exception when object is used as input type incorrectly`() {
        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    |type Query {
                    |    name(filter: Filter): [String]
                    |}
                    |
                    |type Filter {
                    |    filter: String
                    |}
                    """.trimMargin())
                .resolvers(object : GraphQLQueryResolver {
                    fun name(filter: Filter): List<String>? = null
                })
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Type 'Filter' used by argument 'filter' of field 'Query.name' at line 2, column 18 is an object type, " +
            "which can't be used as an input type. Input types are input objects, enums and scalars.")
    }

    @Test
    fun `parser should throw descriptive exception when input object is used as output type incorrectly`() {
        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    |type Query {
                    |    filters: [Filter!]
                    |}
                    |
                    |input Filter {
                    |    filter: String
                    |}
                    """.trimMargin())
                .resolvers(object : GraphQLQueryResolver {
                    fun filters(): List<Filter>? = null
                })
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Type 'Filter' used by field 'Query.filters' at line 2, column 15 is an input object type, " +
            "which can't be used as an output type. Output types are object types, interfaces, unions, enums and scalars.")
    }

    @Test
    fun `parser should throw descriptive exception when input type is not defined`() {
        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    |type Query {
                    |    test(input: TestInput): String
                    |}
                    |
                    |input TestInput {
                    |    nested: [Nested!]
                    |}
                    """.trimMargin())
                .resolvers(object : GraphQLQueryResolver {
                    fun test(input: Map<String, Any?>): String? = null
                })
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Type 'Nested' used by input field 'TestInput.nested' at line 6, column 14 is not defined in the schema.")
    }

    @Test
    fun `parser should throw descriptive exception when type was never reached by the scanner`() {
        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .file("UnreachedType.graphqls")
                .resolvers(object : GraphQLQueryResolver {
                    fun pet(): Pet? = null
                })
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Type 'Cat' used by field 'Pet.friend' at line 6, column 11 in UnreachedType.graphqls is defined in the schema, " +
            "but was not reached while scanning the resolvers and the classes they use, so it wasn't built. " +
            "Return it from a resolver method or one of those classes, or set SchemaParserOptions.includeUnusedTypes and pass a class for it in the parser's dictionary.")
    }

    @Test
    fun `parser should build type that was never reached by the scanner when including unused types`() {
        val schema = SchemaParser.newParser()
            .file("UnreachedType.graphqls")
            .resolvers(object : GraphQLQueryResolver {
                fun pet(): Pet? = null
            })
            .dictionary(Cat::class)
            .options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build())
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
    }

    @Test
    fun `parser should throw descriptive exception when input type is only used by an unused interface`() {
        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    |type Query {
                    |    name: String
                    |}
                    |
                    |interface Searchable {
                    |    search(criteria: Criteria): String
                    |}
                    |
                    |input Criteria {
                    |    text: String
                    |}
                    """.trimMargin())
                .resolvers(object : GraphQLQueryResolver {
                    fun name(): String? = null
                })
                .options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build())
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Type 'Criteria' used by argument 'criteria' of field 'Searchable.search' at line 6, column 22 is defined in the schema, " +
            "but was not reached while scanning the resolvers and the classes they use, so it wasn't built. " +
            "Use it in an argument of a resolver method, directly or through another input type. " +
            "SchemaParserOptions.includeUnusedTypes doesn't include input types.")
    }

    interface Pet {
        val friend: Cat?
    }

    class Cat {
        var name: String? = null
    }

    @Test
    fun `parser handles spring AOP proxied resolvers by default`() {
        val resolver = ProxyFactory(ProxiedResolver()).proxy as GraphQLQueryResolver

        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    test: [String]
                }
                """)
            .resolvers(resolver)
            .build()
    }

    @Test
    fun `parser handles enums with overridden toString method`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                enum CustomEnum {
                    FOO
                }

                type Query {
                    customEnum: CustomEnum
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun customEnum(): CustomEnum? = null
            })
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
    }

    @Test
    fun `parser should include source location for field definition`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                |type Query {
                |    id: ID!
                |}
                """.trimMargin())
            .resolvers(QueryWithIdResolver())
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()

        val sourceLocation = schema.getObjectType("Query")!!
            .getFieldDefinition("id")
            .definition!!.sourceLocation
        assertNotNull(sourceLocation)
        assertEquals(sourceLocation?.line, 2)
        assertEquals(sourceLocation?.column, 5)
        assertNull(sourceLocation?.sourceName)
    }

    @Test
    fun `parser should include source location for field definition when loaded from single classpath file`() {
        val schema = SchemaParser.newParser()
            .file("Test.graphqls")
            .resolvers(QueryWithIdResolver())
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()

        val sourceLocation = schema.getObjectType("Query")!!
            .getFieldDefinition("id")
            .definition!!.sourceLocation
        assertNotNull(sourceLocation)
        assertEquals(sourceLocation?.line, 2)
        assertEquals(sourceLocation?.column, 3)
        assertEquals(sourceLocation?.sourceName, "Test.graphqls")
    }

    @Test
    fun `parser should report syntax error line relative to the schema string containing it`() {
        val error = assertThrows(InvalidSyntaxException::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    |type Query {
                    |    id: ID!
                    |}
                    """.trimMargin())
                .schemaString("type Foo { bar: String!! }")
                .build()
        }

        assertEquals(error.message, "Invalid syntax with offending token '!' at line 1 column 24")
        assertEquals(error.location?.line, 1)
    }

    @Test
    fun `parser should include file name in syntax error`() {
        val error = assertThrows(InvalidSyntaxException::class.java) {
            SchemaParser.newParser()
                .file("InvalidSyntax.graphqls")
                .build()
        }

        assertEquals(error.message, "Invalid syntax with offending token '!' at line 2 column 15 in InvalidSyntax.graphqls")
        assertEquals(error.location?.sourceName, "InvalidSyntax.graphqls")
    }

    @Test
    fun `parser should report unexpected end of the last schema string relative to it`() {
        val error = assertThrows(InvalidSyntaxException::class.java) {
            SchemaParser.newParser()
                // over 1000 lines, so graphql-java's message groups the thousands in the overall line
                .schemaString((1..1100).joinToString("\n") { "type Type$it { id: ID! }" }, "Types.graphqls")
                .schemaString("type Foo {\n    bar: String\n", "Foo.graphqls")
                .build()
        }

        assertEquals(error.message, "Invalid syntax with offending token '<EOF>' at line 3 column 1 in Foo.graphqls")
        assertEquals(error.location?.line, 3)
    }

    @Test
    fun `parser should include source location for field definition in named schema string`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                |schema {
                |    query: Query
                |}
                """.trimMargin(), "Schema.graphqls")
            .schemaString("type Query { id: ID! }", "Query.graphqls")
            .resolvers(QueryWithIdResolver())
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()

        val sourceLocation = schema.getObjectType("Query")!!
            .getFieldDefinition("id")
            .definition!!.sourceLocation
        assertNotNull(sourceLocation)
        assertEquals(sourceLocation?.line, 1)
        assertEquals(sourceLocation?.column, 14)
        assertEquals(sourceLocation?.sourceName, "Query.graphqls")
    }

    @Test
    fun `support enum types if only used as input type`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
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
                """)
            .resolvers(object : GraphQLMutationResolver {
                fun save(input: SaveInput): Boolean = false
            }, object : GraphQLQueryResolver {
                fun test(): Boolean = false
            })
            .dictionary(EnumType::class)
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
    }

    @Test
    fun `support enum types if only used in input Map`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
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
                """)
            .resolvers(object : GraphQLMutationResolver {
                fun save(input: Map<*, *>): Boolean = false
            }, object : GraphQLQueryResolver {
                fun test(): Boolean = false
            })
            .dictionary(EnumType::class)
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
    }

    @Test
    fun `support enum types used in input Map if their class is found elsewhere`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    save(input: SaveInput!): Boolean
                    type: EnumType
                }

                input SaveInput {
                    type: EnumType!
                }

                enum EnumType {
                    TEST
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun save(input: Map<*, *>): Boolean = false
                fun type(): EnumType? = null
            })
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
    }

    @Test
    fun `parser should throw descriptive exception when enum is only used in input Map without a class`() {
        val schema = """
            type Query {
                save(input: SaveInput!): Boolean
            }

            input SaveInput {
                type: EnumType!
            }

            enum EnumType {
                TEST
            }
            """
        val mapError = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(schema)
                .resolvers(object : GraphQLQueryResolver {
                    fun save(input: Map<*, *>): Boolean = false
                })
                .build()
        }
        val subclassError = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(schema)
                .resolvers(object : GraphQLQueryResolver {
                    fun save(input: SaveInputMap): Boolean = false
                })
                .build()
        }

        assertEquals(mapError.message, "Enum type 'EnumType' is used by field 'type' of input type 'SaveInput', but its Java enum can't be determined " +
            "because 'SaveInput' is bound to java.util.Map, which has no type information for its fields. Pass the enum class for type " +
            "'EnumType' in the parser's dictionary, or use a class for 'SaveInput' with a public getter or field 'type'.")
        assertEquals(subclassError.message, "Enum type 'EnumType' is used by field 'type' of input type 'SaveInput', but its Java enum can't be determined " +
            "because ${SaveInputMap::class.java.name} has no public getter or field named 'type'. Pass the enum class for type " +
            "'EnumType' in the parser's dictionary, or use a class for 'SaveInput' with a public getter or field 'type'.")
    }

    @Test
    fun `parser should throw descriptive exception when enum is only used in a nested input without a class`() {
        val error = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        save(input: SaveInput!): Boolean
                    }

                    input SaveInput {
                        nested: NestedInput
                    }

                    input NestedInput {
                        type: EnumType!
                    }

                    enum EnumType {
                        TEST
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun save(input: Map<*, *>): Boolean = false
                })
                .build()
        }

        assertEquals(error.message, "Enum type 'EnumType' is used by field 'type' of input type 'NestedInput', but its Java enum can't be determined " +
            "because no class was found for input type 'NestedInput'. Pass the enum class for type 'EnumType' in the parser's dictionary, " +
            "or use a class for 'NestedInput' with a public getter or field 'type'.")
    }

    @Test
    fun `allow circular relations in input objects`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                input A {
                    id: ID!
                    b: B
                }
                input B {
                    id: ID!
                    a: A
                }
                input C {
                    id: ID!
                    c: C
                }
                type Query { test: Boolean }
                type Mutation {
                    test(input: A!): Boolean
                    testC(input: C!): Boolean
                }
                """)
            .resolvers(object : GraphQLMutationResolver {
                fun test(a: A): Boolean {
                    return true
                }

                fun testC(c: C): Boolean {
                    return true
                }
            }, object : GraphQLQueryResolver {
                fun test(): Boolean = false
            })
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
    }

    class A {
        var id: String? = null
        var b: B? = null
    }

    class B {
        var id: String? = null
        var a: A? = null
    }

    class C {
        var id: String? = null
        var c: C? = null
    }

    @Test
    fun `interface implementing an interface should have non-empty interface list`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                interface Trait {
                    id: ID!
                }
                interface MammalTrait implements Trait {
                    id: ID!
                }
                type PoodleTrait implements Trait & MammalTrait {
                    id: ID!
                }

                interface Animal {
                    id: ID!
                    traits: [Trait]
                }
                interface Dog implements Animal {
                    id: ID!
                    traits: [MammalTrait]
                }
                type Poodle implements Animal & Dog {
                    id: ID!
                    traits: [PoodleTrait]
                }

                type Query { test: [Poodle] }
                """)
            .resolvers(MultiLevelInterfaceResolver())
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
        val traitInterface = schema.getType("Trait") as GraphQLInterfaceType
        val animalInterface = schema.getType("Animal") as GraphQLInterfaceType
        val mammalTraitInterface = schema.getType("MammalTrait") as GraphQLInterfaceType
        val dogInterface = schema.getType("Dog") as GraphQLInterfaceType
        val poodleObject = schema.getType("Poodle") as GraphQLObjectType
        val poodleTraitObject = schema.getType("PoodleTrait") as GraphQLObjectType

        assert(poodleObject.interfaces.containsAll(listOf(dogInterface, animalInterface)))
        assert(poodleTraitObject.interfaces.containsAll(listOf(mammalTraitInterface, traitInterface)))
        assert(dogInterface.interfaces.contains(animalInterface))
        assert(mammalTraitInterface.interfaces.contains(traitInterface))
        assert(traitInterface.definition!!.implements.isEmpty())
        assert(animalInterface.definition!!.implements.isEmpty())
    }

    class MultiLevelInterfaceResolver : GraphQLQueryResolver {
        fun test(): List<Poodle> = listOf()

        interface Trait {
            var id: String
        }

        interface MammalTrait : Trait {
            override var id: String
        }

        interface PoodleTrait : MammalTrait {
            override var id: String
        }

        abstract class Animal<T : Trait> {
            var id: String? = null
            abstract var traits: List<T>
        }

        abstract class Dog<T : MammalTrait> : Animal<T>() {
            abstract override var traits: List<T>
        }

        class Poodle(override var traits: List<PoodleTrait>) : Dog<PoodleTrait>()
    }

    @Test
    fun `NonNull and nullable input arguments should resolve to GraphQLInputObjectType`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    testNonNullable(filter: Filter!): Boolean
                    testNullable(filter: Filter): Boolean
                }
        
                input Filter {
                    filter: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun testNonNullable(filter: Filter): Boolean = false
                fun testNullable(filter: Filter): Boolean = false
            })
            .directiveWiring(object : SchemaDirectiveWiring {
                override fun onArgument(environment: SchemaDirectiveWiringEnvironment<GraphQLArgument>): GraphQLArgument {
                    when (environment.element.type) {
                        is GraphQLNonNull ->
                            assert((environment.element.type as GraphQLNonNull).wrappedType is GraphQLInputObjectType)
                    }
                    return environment.element
                }
            })
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()

        val testNonNullableArgument = schema.getObjectType("Query")!!
            .getFieldDefinition("testNonNullable")
            .arguments.first()
        val testNullableArgument = schema.getObjectType("Query")!!
            .getFieldDefinition("testNullable")
            .arguments.first()
        assert(testNonNullableArgument.type is GraphQLNonNull)
        assert((testNonNullableArgument.type as GraphQLNonNull).wrappedType is GraphQLInputObjectType)
        assert(testNullableArgument.type is GraphQLInputObjectType)
    }

    @Test
    fun `input objects behind list and non-null input fields should resolve to GraphQLInputObjectType during directive wiring`() {
        val wiredFieldTypes = mutableMapOf<String, String>()
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    test(filter: NestedFilter): Boolean
                }

                input NestedFilter {
                    subs: [SubFilter!]
                    other: OtherFilter!
                }

                input SubFilter {
                    parent: NestedFilter
                }

                input OtherFilter {
                    name: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun test(filter: NestedFilter?): Boolean = false
            })
            .directiveWiring(object : SchemaDirectiveWiring {
                override fun onInputObjectField(environment: SchemaDirectiveWiringEnvironment<GraphQLInputObjectField>): GraphQLInputObjectField {
                    val type = GraphQLTypeUtil.unwrapAllAs<GraphQLType>(environment.element.type)
                    if (type !is GraphQLScalarType) {
                        wiredFieldTypes[environment.element.name] = type.javaClass.simpleName
                    }
                    return environment.element
                }
            })
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()

        // parent points back at NestedFilter while it's still being built, so it stays a type reference until the schema is built
        assertEquals(wiredFieldTypes, mapOf(
            "subs" to "GraphQLInputObjectType",
            "other" to "GraphQLInputObjectType",
            "parent" to "GraphQLTypeReference"
        ))
    }

    @Test
    fun `parser should use comments for descriptions`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    "description"
                    description: String
                    #comment
                    comment: String
                    omitted: String
                    "description"
                    #comment
                    both: String
                    ""
                    empty: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {})
            .options(SchemaParserOptions.newOptions().allowUnimplementedResolvers(true).build())
            .build()
            .makeExecutableSchema()

        val queryType = schema.getObjectType("Query")!!
        assertEquals(queryType.getFieldDefinition("description").description, "description")
        assertEquals(queryType.getFieldDefinition("comment").description, "comment")
        assertNull(queryType.getFieldDefinition("omitted").description)
        assertEquals(queryType.getFieldDefinition("both").description, "description")
        assertEquals(queryType.getFieldDefinition("empty").description, "")
    }

    @Test
    fun `parser should not use comments for descriptions`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    "description"
                    description: String
                    #comment
                    comment: String
                    omitted: String
                    "description"
                    #comment
                    both: String
                    ""
                    empty: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {})
            .options(SchemaParserOptions.newOptions().useCommentsForDescriptions(false).allowUnimplementedResolvers(true).build())
            .build()
            .makeExecutableSchema()

        assertEquals(schema.queryType.getFieldDefinition("description").description, "description")
        assertNull(schema.queryType.getFieldDefinition("comment").description)
        assertNull(schema.queryType.getFieldDefinition("omitted").description)
        assertEquals(schema.queryType.getFieldDefinition("both").description, "description")
        assertEquals(schema.queryType.getFieldDefinition("empty").description, "")
    }

    @Test
    fun `parser should include schema descriptions when declared`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                "This is a schema level description"
                schema {
                  query: SubstituteQuery
                }

                type SubstituteQuery {
                    description: String
                    comment: String
                    omitted: String
                    both: String
                    empty: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {})
            .options(SchemaParserOptions.newOptions().allowUnimplementedResolvers(true).build())
            .build()
            .makeExecutableSchema()

        assertEquals(schema.description, "This is a schema level description")
    }

    @Test
    fun `parser should return null schema description when not declared`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                schema {
                  query: SubstituteQuery
                }

                type SubstituteQuery {
                    description: String
                    comment: String
                    omitted: String
                    both: String
                    empty: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {})
            .options(SchemaParserOptions.newOptions().allowUnimplementedResolvers(true).build())
            .build()
            .makeExecutableSchema()

        assertNull(schema.description)
    }

    @Test
    fun `parser should use root types declared in schema extensions`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                "This is a schema level description"
                schema {
                    query: SubstituteQuery
                }

                extend schema {
                    mutation: SubstituteMutation
                }

                directive @contact(name: String!) on SCHEMA

                extend schema @contact(name: "books-team")

                type SubstituteQuery {
                    query: String
                }

                type SubstituteMutation {
                    mutation: String
                }
                """)
            .resolvers(
                object : GraphQLQueryResolver {
                    fun query(): String? = null
                },
                object : GraphQLMutationResolver {
                    fun mutation(): String? = null
                })
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()

        assertEquals(schema.queryType.name, "SubstituteQuery")
        assertEquals(schema.mutationType?.name, "SubstituteMutation")
        assertEquals(schema.description, "This is a schema level description")
    }

    enum class EnumType {
        TEST
    }

    class SaveInput {
        var type: EnumType? = null
    }

    class SaveInputMap : HashMap<String, Any?>()

    class QueryWithIdResolver : GraphQLQueryResolver {
        fun getId(): String = "1"
    }

    class Filter {
        var filter: String? = null
    }

    data class NestedFilter(val subs: List<SubFilter>?, val other: OtherFilter)

    data class SubFilter(val parent: NestedFilter?)

    data class OtherFilter(val name: String?)

    class CustomGenericWrapper<T, V>(val value: V)

    class Obj {
        fun name() = "name"
    }

    class AnotherObj {
        fun key() = null
    }

    class RootObj

    class ProxiedResolver : GraphQLQueryResolver {
        fun test(): List<String> = listOf()
    }

    enum class CustomEnum {
        FOO {
            override fun toString(): String {
                return "Bar"
            }
        }
    }

    @Test
    fun `parser should verify subscription resolver return type`() {
        class Subscription : GraphQLSubscriptionResolver {
            fun onItemCreated(env: DataFetchingEnvironment) = env.hashCode()
        }

        val error = assertThrows(FieldResolverError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Subscription {
                        onItemCreated: Int!
                    }

                    type Query {
                        test: String
                    }
                    """
                )
                .resolvers(
                    Subscription(),
                    object : GraphQLQueryResolver { fun test() = "test" }
                )
                .build()
                .makeExecutableSchema()
        }

        val expected = """
            No method or field found as defined in schema <unknown>:3 with any of the following signatures (with or without one of [interface graphql.schema.DataFetchingEnvironment, class graphql.GraphQLContext] as the last argument), in priority order:

              graphql.kickstart.tools.SchemaParserTest${"$"}parser should verify subscription resolver return type${"$"}Subscription.onItemCreated()
              graphql.kickstart.tools.SchemaParserTest${"$"}parser should verify subscription resolver return type${"$"}Subscription.getOnItemCreated()
              graphql.kickstart.tools.SchemaParserTest${"$"}parser should verify subscription resolver return type${"$"}Subscription.onItemCreated

            Note that a Subscription data fetcher must return a Publisher of events
        """.trimIndent()

        assertEquals(error.message, expected)
    }

    @Test
    fun `parser should verify subscription resolver generic future return type`() {
        class Subscription : GraphQLSubscriptionResolver {
            fun onItemCreated(env: DataFetchingEnvironment) = completedFuture(env.hashCode())
        }

        val error = assertThrows(FieldResolverError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Subscription {
                        onItemCreated: Int!
                    }

                    type Query {
                        test: String
                    }
                    """
                )
                .resolvers(
                    Subscription(),
                    object : GraphQLQueryResolver { fun test() = "test" }
                )
                .build()
                .makeExecutableSchema()
        }

        val expected = """
            No method or field found as defined in schema <unknown>:3 with any of the following signatures (with or without one of [interface graphql.schema.DataFetchingEnvironment, class graphql.GraphQLContext] as the last argument), in priority order:

              graphql.kickstart.tools.SchemaParserTest${"$"}parser should verify subscription resolver generic future return type${"$"}Subscription.onItemCreated()
              graphql.kickstart.tools.SchemaParserTest${"$"}parser should verify subscription resolver generic future return type${"$"}Subscription.getOnItemCreated()
              graphql.kickstart.tools.SchemaParserTest${"$"}parser should verify subscription resolver generic future return type${"$"}Subscription.onItemCreated

            Note that a Subscription data fetcher must return a Publisher of events
        """.trimIndent()

        assertEquals(error.message, expected)
    }

    @Test
    fun `parser should find mutation methods on a resolver that is also a subscription resolver`() {
        class MutationAndSubscription : GraphQLMutationResolver, GraphQLSubscriptionResolver {
            fun addItem(name: String) = name
            fun onItemAdded(): Publisher<String> = StringPublisher("added")
        }

        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    test: String
                }

                type Mutation {
                    addItem(name: String!): String!
                }

                type Subscription {
                    onItemAdded: String!
                }
                """
            )
            .resolvers(
                MutationAndSubscription(),
                object : GraphQLQueryResolver { fun test() = "test" }
            )
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) { """mutation { addItem(name: "item") }""" }

        assertEquals(data["addItem"], "item")
        assertEquals(gql.firstSubscriptionEvent("subscription { onItemAdded }"), mapOf("onItemAdded" to "added"))
    }

    @Test
    fun `parser should find query methods on a resolver that is also a subscription resolver`() {
        class QueryAndSubscription : GraphQLQueryResolver, GraphQLSubscriptionResolver {
            fun item() = "item"
            fun onItemAdded(): Publisher<String> = StringPublisher("added")
        }

        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    item: String!
                }

                type Subscription {
                    onItemAdded: String!
                }
                """
            )
            .resolvers(QueryAndSubscription())
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) { "{ item }" }

        assertEquals(data["item"], "item")
        assertEquals(gql.firstSubscriptionEvent("subscription { onItemAdded }"), mapOf("onItemAdded" to "added"))
    }

    @Test
    fun `parser should verify subscription return type on a resolver that is also a mutation resolver`() {
        class MutationAndSubscription : GraphQLMutationResolver, GraphQLSubscriptionResolver {
            fun addItem(name: String) = name
            fun onItemAdded() = "added"
        }

        val error = assertThrows(FieldResolverError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        test: String
                    }

                    type Mutation {
                        addItem(name: String!): String!
                    }

                    type Subscription {
                        onItemAdded: String!
                    }
                    """
                )
                .resolvers(
                    MutationAndSubscription(),
                    object : GraphQLQueryResolver { fun test() = "test" }
                )
                .build()
                .makeExecutableSchema()
        }

        assert(error.message!!.contains("onItemAdded()"))
        assert(error.message!!.endsWith("Note that a Subscription data fetcher must return a Publisher of events"))
    }

    @Test
    fun `parser should not mention subscriptions for a missing mutation method on a resolver that is also a subscription resolver`() {
        class MutationAndSubscription : GraphQLMutationResolver, GraphQLSubscriptionResolver {
            fun onItemAdded(): Publisher<String> = StringPublisher("added")
        }

        val error = assertThrows(FieldResolverError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        test: String
                    }

                    type Mutation {
                        addItem(name: String!): String!
                    }

                    type Subscription {
                        onItemAdded: String!
                    }
                    """
                )
                .resolvers(
                    MutationAndSubscription(),
                    object : GraphQLQueryResolver { fun test() = "test" }
                )
                .build()
                .makeExecutableSchema()
        }

        assert(error.message!!.contains("addItem(~name)"))
        assert(!error.message!!.contains("Subscription data fetcher")) { error.message!! }
    }

    @Test
    fun `parser should accept subscription resolvers returning publisher subtypes`() {
        class Subscription : OverriddenPublisherSubscription {
            fun onCustomPublisher(): EventPublisher<Event> = SingleEventPublisher("onCustomPublisher")
            fun onConcretePublisher() = SingleEventPublisher("onConcretePublisher")
            override fun onOverriddenPublisher() = SingleEventPublisher("onOverriddenPublisher")
        }

        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    test: String
                }

                type Subscription {
                    onCustomPublisher: Event!
                    onConcretePublisher: Event!
                    onOverriddenPublisher: Event!
                }

                type Event {
                    name: String!
                }
                """
            )
            .resolvers(
                Subscription(),
                object : GraphQLQueryResolver { fun test() = "test" }
            )
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
        val gql = GraphQL.newGraphQL(schema).build()

        listOf("onCustomPublisher", "onConcretePublisher", "onOverriddenPublisher").forEach { field ->
            assertEquals(gql.firstSubscriptionEvent("subscription { $field { name } }"), mapOf(field to mapOf("name" to field)))
        }
    }

    @Test
    fun `parser should accept subscription resolvers returning futures of publishers`() {
        class Subscription : GraphQLSubscriptionResolver {
            fun onCompletableFuture(): CompletableFuture<Publisher<Event>> = completedFuture(SingleEventPublisher("onCompletableFuture"))
            fun onCompletionStage(): CompletionStage<Publisher<Event>> = completedStage(SingleEventPublisher("onCompletionStage"))
            fun onFuture(): Future<Publisher<Event>> = completedFuture(SingleEventPublisher("onFuture"))
            fun onConcretePublisherFuture(): CompletableFuture<SingleEventPublisher> = completedFuture(SingleEventPublisher("onConcretePublisherFuture"))
            fun onWildcardPublisherFuture(): CompletionStage<out Publisher<Event>> = completedStage(SingleEventPublisher("onWildcardPublisherFuture"))
        }

        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    test: String
                }

                type Subscription {
                    onCompletableFuture: Event!
                    onCompletionStage: Event!
                    onFuture: Event!
                    onConcretePublisherFuture: Event!
                    onWildcardPublisherFuture: Event!
                }

                type Event {
                    name: String!
                }
                """
            )
            .resolvers(
                Subscription(),
                object : GraphQLQueryResolver { fun test() = "test" }
            )
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
        val gql = GraphQL.newGraphQL(schema).build()

        listOf("onCompletableFuture", "onCompletionStage", "onFuture", "onConcretePublisherFuture", "onWildcardPublisherFuture").forEach { field ->
            assertEquals(gql.firstSubscriptionEvent("subscription { $field { name } }"), mapOf(field to mapOf("name" to field)))
        }
    }

    @Test
    fun `parser should accept subscription resolvers returning a receive channel subtype with a wrapper for it`() {
        class Subscription : GraphQLSubscriptionResolver {
            fun onChannel(): Channel<String> = Channel<String>(1).also { it.trySend("onChannel") }
        }

        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    test: String
                }

                type Subscription {
                    onChannel: String!
                }
                """
            )
            .resolvers(
                Subscription(),
                object : GraphQLQueryResolver { fun test() = "test" }
            )
            .options(
                SchemaParserOptions.newOptions()
                    .genericWrappers(SchemaParserOptions.GenericWrapper.withTransformer(Channel::class, 0, { channel: Channel<*> ->
                        publish { for (item in channel) send(item) }
                    }))
                    .build()
            )
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()
        val gql = GraphQL.newGraphQL(schema).build()

        assertEquals(gql.firstSubscriptionEvent("subscription { onChannel }"), mapOf("onChannel" to "onChannel"))
    }

    private fun GraphQL.firstSubscriptionEvent(query: String): Map<String, Any>? {
        val result = execute(query)
        assert(result.errors.isEmpty()) { result.errors.toString() }

        val subscriber = TestEnvironment().newManualSubscriber(result.getData<Publisher<ExecutionResult>>())
        return subscriber.requestNextElement().getData<Map<String, Any>>()
    }

    interface EventPublisher<T> : Publisher<T>

    class StringPublisher(value: String) : Publisher<String> by publish(block = { send(value) })

    data class Event(val name: String)

    class SingleEventPublisher(name: String) : EventPublisher<Event>, Publisher<Event> by publish(block = { send(Event(name)) })

    interface OverriddenPublisherSubscription : GraphQLSubscriptionResolver {
        fun onOverriddenPublisher(): Publisher<Event>
    }
}
