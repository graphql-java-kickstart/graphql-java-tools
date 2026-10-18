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
        schema.assertEveryFieldResolves()

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
        schema.assertEveryFieldResolves()

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
        schema.assertEveryFieldResolves()

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
        schema.assertEveryFieldResolves()

        assertNotNull(schema.directivesByName["doSomething"]?.getArgument("one")?.directivesByName?.get("somethingElse"))
        assertNotNull(schema.directivesByName["somethingElse"]?.getArgument("two")?.directivesByName?.get("doSomething"))
    }

    @Test
    fun `scanner builds enums used only by directives from their value names`() {
        val parser = SchemaParser.newParser()
            .schemaString(linkDirective)
            .schemaString("type Query { hello: String }")
            .resolvers(helloResolver)
            .build()
        val schema = parser.makeExecutableSchema()
        schema.assertEveryFieldResolves()

        val purpose = schema.getType("Purpose") as GraphQLEnumType
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

                extend schema
                    @link(url: "https://specs.apollo.dev/federation/v2.0")
                    @link(url: "https://specs.apollo.dev/link/v1.0", for: SECURITY)

                type Query { hello: String }
                """)
            .resolvers(helloResolver)
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()

        assertEquals(schema.getSchemaAppliedDirectives("link").map { it.getArgument("for")?.getValue<String>() }, listOf("EXECUTION", "SECURITY"))
        assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) { IntrospectionQuery.INTROSPECTION_QUERY }

        val printed = SchemaPrinter(SchemaPrinter.Options.defaultOptions().includeSchemaDefinition(true)).print(schema)
        assert(printed.contains("directive @link(for: Purpose = EXECUTION, url: String!) repeatable on SCHEMA")) { printed }
        assert(printed.contains("""@link(for : SECURITY, url : "https://specs.apollo.dev/link/v1.0")""")) { printed }
    }

    @Test
    fun `scanner uses the dictionary class of enums used only by directives`() {
        val schema = SchemaParser.newParser()
            .schemaString(linkDirective)
            .schemaString("type Query { hello: String }")
            .resolvers(helloResolver)
            .dictionary(Purpose::class)
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()

        assertEquals((schema.getType("Purpose") as GraphQLEnumType).values.map { it.value }, Purpose.entries)
    }

    @Test
    fun `scanner uses enum classes found through unused types for enums used by directives when option is true`() {
        val schema = SchemaParser.newParser()
            .schemaString(linkDirective)
            .schemaString(
                """
                type Query { hello: String }

                type Holder { purpose: Purpose }
                """)
            .resolvers(helloResolver)
            .dictionary(Holder::class)
            .options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build())
            .build()
            .makeExecutableSchema()
        schema.assertEveryFieldResolves()

        assertEquals((schema.getType("Purpose") as GraphQLEnumType).values.map { it.value }, Purpose.entries)
    }

    @Test
    fun `scanner requires a Java enum for enums used by directives and by fields`() {
        val missingEnum = "Expected enum with name 'Purpose' but found none!"

        // a resolver parameter of another class
        assertSchemaError(
            SchemaParser.newParser()
                .schemaString(linkDirective)
                .schemaString("type Query { hello(purpose: Purpose!): String }")
                .resolvers(object : GraphQLQueryResolver { fun hello(purpose: String): String = purpose }),
            "Type 'Purpose' is declared as an enum in the GraphQL schema but is not a Java enum!")

        // a resolver parameter of type Object
        assertSchemaError(
            SchemaParser.newParser()
                .schemaString(linkDirective)
                .schemaString("type Query { hello(purpose: Purpose!): String }")
                .resolvers(object : GraphQLQueryResolver { fun hello(purpose: Any): String = purpose.toString() }),
            missingEnum)

        // a property of type Object on an unused type
        assertSchemaError(
            SchemaParser.newParser()
                .schemaString(linkDirective)
                .schemaString(
                    """
                    type Query { hello: String }

                    type ObjectHolder { purpose: Purpose }
                    """)
                .resolvers(helloResolver)
                .dictionary(ObjectHolder::class)
                .options(SchemaParserOptions.newOptions().includeUnusedTypes(true).build()),
            missingEnum)

        // an input field bound to a Map, which would get the enum's values
        val mapError = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(linkDirective)
                .schemaString(
                    """
                    input Filter { purpose: Purpose }

                    type Query { hello(filter: Filter!): String }
                    """)
                .resolvers(object : GraphQLQueryResolver { fun hello(filter: Map<String, Any>): String = filter.toString() })
                .build()
        }
        assertEquals(mapError.message, "Enum type 'Purpose' is used by field 'purpose' of input type 'Filter', but its Java enum can't be determined " +
            "because 'Filter' is bound to java.util.Map, which has no type information for its fields. Pass the enum class for type " +
            "'Purpose' in the parser's dictionary, or use a class for 'Filter' with a public getter or field 'purpose'.")

        // an argument of an unimplemented field added by an extension
        assertSchemaError(
            SchemaParser.newParser()
                .schemaString(linkDirective)
                .schemaString(
                    """
                    type Query { hello: String }

                    extend type Query { unimplemented(purpose: Purpose): String }
                    """)
                .resolvers(helloResolver)
                .options(SchemaParserOptions.newOptions().allowUnimplementedResolvers(true).build()),
            missingEnum)
    }

    private fun assertSchemaError(parser: SchemaParserBuilder, message: String) {
        val error = assertThrows(SchemaError::class.java) { parser.build().makeExecutableSchema() }
        assertEquals(error.message, message)
    }

    // the enum and directive from issue #823
    private val linkDirective = """
        enum Purpose { SECURITY EXECUTION }

        directive @link(url: String!, for: Purpose) repeatable on SCHEMA
        """

    private val helloResolver = object : GraphQLQueryResolver { fun hello(): String = "hello" }

    enum class Purpose { SECURITY, EXECUTION }

    class Holder(val purpose: Purpose?)

    class ObjectHolder(val purpose: Any?)
}
