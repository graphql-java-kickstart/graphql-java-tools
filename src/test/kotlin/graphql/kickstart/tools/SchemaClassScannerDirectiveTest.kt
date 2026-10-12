package graphql.kickstart.tools

import graphql.GraphQL
import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.introspection.IntrospectionQuery
import graphql.kickstart.tools.SchemaClassScannerDirectiveTest.CustomEnum.ONE
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.SchemaPrinter
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.*

class SchemaClassScannerDirectiveTest {

    @Test
    fun `scanner should handle directives with scalar input value`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                scalar CustomValue
                directive @doSomething(value: CustomValue) on FIELD_DEFINITION 

                type Query {
                    string: String @doSomething(value: "some thing")
                }
                """)
            .resolvers(object : GraphQLQueryResolver { fun string(): String = "hello" })
            .scalars(customValueScalar)
            .build()
            .makeExecutableSchema()

        val value = schema.queryType.getFieldDefinition("string")
            .getAppliedDirective("doSomething")
            .getArgument("value")!!
            .getValue<CustomValue>()

        assertEquals(value.value, "some thing")
    }

    data class CustomValue(val value: String?)
    private val customValueScalar: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("CustomValue")
        .coercing(object : Coercing<CustomValue, String> {
            override fun serialize(input: Any, context: GraphQLContext, locale: Locale) = input.toString()
            override fun parseValue(input: Any, context: GraphQLContext, locale: Locale) =
                CustomValue(input.toString())
            override fun parseLiteral(input: Value<*>, variables: CoercedVariables, context: GraphQLContext, locale: Locale) =
                CustomValue((input as StringValue).value)
        })
        .build()

    @Test
    fun `scanner should handle directives with enum input value`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                enum CustomEnum { ONE TWO THREE }
                directive @doSomething(value: CustomEnum) on FIELD_DEFINITION 

                type Query {
                    string: String @doSomething(value: ONE)
                    another: CustomEnum
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun string(): String = "hello"
                fun another(): CustomEnum = ONE
            })
            .scalars(customValueScalar)
            .build()
            .makeExecutableSchema()

        val value = schema.queryType.getFieldDefinition("string")
            .getAppliedDirective("doSomething")
            .getArgument("value")!!
            .getValue<CustomEnum>()

        assertEquals(value, ONE)
    }

    enum class CustomEnum { ONE, TWO, THREE}

    @Test
    fun `scanner should handle directives with input object input value`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                input CustomInput { value: String }
                directive @doSomething(input: CustomInput) on FIELD_DEFINITION
                
                type Query {
                    string: String @doSomething(input: { value: "some value" })
                    another(input: CustomInput): String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun string(): String = "hello"
                fun another(input: CustomInput): String = input.value
            })
            .scalars(customValueScalar)
            .build()
            .makeExecutableSchema()

        val value = schema.queryType.getFieldDefinition("string")
            .getAppliedDirective("doSomething")
            .getArgument("input")!!
            .getValue<Map<*,*>>()["value"]

        assertEquals(value, "some value")
    }

    data class CustomInput(val value: String)

    @Test
    fun `scanner should handle directives with arguments with directives`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @doSomething(one: String @somethingElse) on FIELD_DEFINITION | ARGUMENT_DEFINITION
                directive @somethingElse(two: String @doSomething) on FIELD_DEFINITION | ARGUMENT_DEFINITION
                
                type Query {
                    string: String @doSomething(one: "sss")
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun string(): String = "hello"
            })
            .scalars(customValueScalar)
            .build()
            .makeExecutableSchema()

        assertNotNull(schema.directivesByName["doSomething"]?.getArgument("one")?.directivesByName?.get("somethingElse"))
        assertNotNull(schema.directivesByName["somethingElse"]?.getArgument("two")?.directivesByName?.get("doSomething"))
    }

    @Test
    fun `scanner builds enums used only by directives from their value names`() {
        val parser = SchemaParser.newParser()
            .schemaString(
                """
                enum Purpose { SECURITY EXECUTION }

                directive @link(url: String!, for: Purpose) repeatable on SCHEMA

                type Query { hello: String }
                """)
            .resolvers(object : GraphQLQueryResolver { fun hello(): String = "hello" })
            .build()
        val schema = parser.makeExecutableSchema()

        val purpose = schema.getType("Purpose") as GraphQLEnumType
        assertEquals(purpose.values.map { it.name }, listOf("SECURITY", "EXECUTION"))
        assertEquals(purpose.values.map { it.value }, listOf<Any>("SECURITY", "EXECUTION"))
        assertEquals(schema.getDirective("link")?.getArgument("for")?.type, purpose)
        assertEquals(parser.getUnusedDefinitions(), emptySet())
    }

    @Test
    fun `scanner builds applied directives with enums used only by directives`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                enum Purpose { SECURITY EXECUTION }

                directive @link(url: String!, for: Purpose = EXECUTION) repeatable on SCHEMA
                directive @purpose(for: Purpose!) on FIELD_DEFINITION

                extend schema
                    @link(url: "https://specs.apollo.dev/federation/v2.0")
                    @link(url: "https://specs.apollo.dev/link/v1.0", for: SECURITY)

                type Query {
                    hello: String @purpose(for: SECURITY)
                }
                """)
            .resolvers(object : GraphQLQueryResolver { fun hello(): String = "hello" })
            .build()
            .makeExecutableSchema()

        assertEquals(schema.getSchemaAppliedDirectives("link").map { it.getArgument("for")?.getValue<String>() }, listOf("EXECUTION", "SECURITY"))
        assertEquals(schema.queryType.getField("hello").getAppliedDirective("purpose").getArgument("for")?.getValue<String>(), "SECURITY")
        assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) { IntrospectionQuery.INTROSPECTION_QUERY }

        val printed = SchemaPrinter(SchemaPrinter.Options.defaultOptions().includeSchemaDefinition(true)).print(schema)
        assert(printed.contains("directive @link(for: Purpose = EXECUTION, url: String!) repeatable on SCHEMA")) { printed }
        assert(printed.contains("""@link(for : SECURITY, url : "https://specs.apollo.dev/link/v1.0")""")) { printed }
    }

    @Test
    fun `scanner builds the federation link directive without a class for its enum`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @link(
                    url: String!,
                    as: String,
                    import: [Import],
                    for: Purpose)
                repeatable on SCHEMA

                scalar Import

                enum Purpose {
                  SECURITY
                  EXECUTION
                }

                extend schema @link(url: "https://specs.apollo.dev/federation/v2.0", import: ["@key"])

                type Query { hello: String }
                """)
            .resolvers(object : GraphQLQueryResolver { fun hello(): String = "hello" })
            .scalars(importScalar)
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Purpose") as GraphQLEnumType).values.map { it.value }, listOf<Any>("SECURITY", "EXECUTION"))
        assertEquals(schema.getSchemaAppliedDirective("link").getArgument("import")?.getValue<List<String>>(), listOf("@key"))
    }

    private val importScalar: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("Import")
        .coercing(object : Coercing<String, String> {
            override fun serialize(input: Any, context: GraphQLContext, locale: Locale) = input.toString()
            override fun parseValue(input: Any, context: GraphQLContext, locale: Locale) = input.toString()
            override fun parseLiteral(input: Value<*>, variables: CoercedVariables, context: GraphQLContext, locale: Locale) =
                (input as StringValue).value
        })
        .build()

    @Test
    fun `scanner uses the class of enums used by both directives and resolvers`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                enum Purpose { SECURITY EXECUTION }

                directive @purpose(for: Purpose!) on FIELD_DEFINITION

                type Query {
                    hello(purpose: Purpose!): String @purpose(for: SECURITY)
                }
                """)
            .resolvers(object : GraphQLQueryResolver { fun hello(purpose: Purpose): String = purpose.name })
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Purpose") as GraphQLEnumType).values.map { it.value }, Purpose.entries)
        assertEquals(schema.queryType.getField("hello").getAppliedDirective("purpose").getArgument("for")?.getValue<Purpose>(), Purpose.SECURITY)

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                hello(purpose: EXECUTION)
            }
            """
        }

        assertEquals(data["hello"], "EXECUTION")
    }

    @Test
    fun `scanner requires a Java enum for enums used by directives and resolver parameters of another class`() {
        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    enum Purpose { SECURITY EXECUTION }

                    directive @purpose(for: Purpose!) on FIELD_DEFINITION

                    type Query {
                        hello(purpose: Purpose!): String
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver { fun hello(purpose: String): String = purpose })
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Type 'Purpose' is declared as an enum in the GraphQL schema but is not a Java enum!")
    }

    @Test
    fun `scanner requires a Java enum for enums used by directives and resolver parameters of type Object`() {
        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    enum Purpose { SECURITY EXECUTION }

                    directive @purpose(for: Purpose!) on FIELD_DEFINITION

                    type Query {
                        hello(purpose: Purpose!): String
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver { fun hello(purpose: Any): String = purpose.toString() })
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Expected enum with name 'Purpose' but found none!")
    }

    @Test
    fun `scanner uses the dictionary class of enums used only by directives`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                enum Purpose { SECURITY EXECUTION }

                directive @link(url: String!, for: Purpose) repeatable on SCHEMA

                type Query { hello: String }
                """)
            .resolvers(object : GraphQLQueryResolver { fun hello(): String = "hello" })
            .dictionary(Purpose::class)
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Purpose") as GraphQLEnumType).values.map { it.value }, Purpose.entries)
    }

    @Test
    fun `scanner uses enum classes found through unused types for enums used by directives when option is true`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                enum Purpose { SECURITY EXECUTION }

                directive @link(url: String!, for: Purpose) repeatable on SCHEMA

                type Query { hello: String }

                type Holder { purpose: Purpose }
                """)
            .resolvers(object : GraphQLQueryResolver { fun hello(): String = "hello" })
            .dictionary(Holder::class)
            .options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build())
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Purpose") as GraphQLEnumType).values.map { it.value }, Purpose.entries)
    }

    class Holder(val purpose: Purpose?)

    @Test
    fun `scanner builds enums used by directives and by input fields without a property from their names`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                enum Purpose { SECURITY EXECUTION }

                directive @link(url: String!, for: Purpose) repeatable on SCHEMA

                input Filter { purpose: Purpose }

                type Query {
                    hello(filter: Filter!): String
                }
                """)
            .resolvers(object : GraphQLQueryResolver { fun hello(filter: Map<String, Any>): String = filter["purpose"] as String })
            .build()
            .makeExecutableSchema()

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                hello(filter: { purpose: SECURITY })
            }
            """
        }

        // a Map has no property for the field to find the enum's class through, so the resolver gets the value's name
        assertEquals(data["hello"], "SECURITY")
    }

    enum class Purpose { SECURITY, EXECUTION }
}
