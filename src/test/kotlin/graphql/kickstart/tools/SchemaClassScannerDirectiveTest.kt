package graphql.kickstart.tools

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.kickstart.tools.SchemaClassScannerDirectiveTest.CustomEnum.ONE
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.GraphQLScalarType
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
            .getArgument("value")
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
            .getArgument("value")
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
            .getArgument("input")
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
}
