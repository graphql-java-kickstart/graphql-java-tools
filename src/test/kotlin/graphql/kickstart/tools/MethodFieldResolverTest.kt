package graphql.kickstart.tools

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.language.StringValue
import graphql.schema.Coercing
import graphql.schema.GraphQLScalarType
import org.junit.Assert
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.*

class MethodFieldResolverTest {

    @Test
    fun `should handle Optional type as method input argument`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    testValue(input: String): String
                    testOmitted(input: String): String
                    testNull(input: String): String
                }
                """)
            .scalars(customScalarType)
            .resolvers(object : GraphQLQueryResolver {
                fun testValue(input: Optional<String>) = input.toString()
                fun testOmitted(input: Optional<String>) = input.toString()
                fun testNull(input: Optional<String>) = input.toString()
            })
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema).build()

        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            query {
                testValue(input: "test-value")
                testOmitted
                testNull(input: null)
            }
            """)
            .context(Object())
            .root(Object()))

        val expected = mapOf(
            "testValue" to "Optional[test-value]",
            "testOmitted" to "Optional.empty",
            "testNull" to "Optional.empty"
        )

        Assert.assertEquals(expected, result.getData())
    }

    @Test
    fun `should handle Optional type as method input argument with omission detection`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    testValue(input: String): String
                    testOmitted(input: String): String
                    testNull(input: String): String
                }
                """)
            .scalars(customScalarType)
            .resolvers(object : GraphQLQueryResolver {
                fun testValue(input: Optional<String>) = input.toString()
                fun testOmitted(input: Optional<String>?) = input.toString()
                fun testNull(input: Optional<String>) = input.toString()
            })
            .options(SchemaParserOptions.newOptions()
                .inputArgumentOptionalDetectOmission(true)
                .build())
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema).build()

        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            query {
                testValue(input: "test-value")
                testOmitted
                testNull(input: null)
            }
            """)
            .context(Object())
            .root(Object()))

        val expected = mapOf(
            "testValue" to "Optional[test-value]",
            "testOmitted" to "null",
            "testNull" to "Optional.empty"
        )

        Assert.assertEquals(expected, result.getData())
    }

    @Test
    fun `should handle scalar types as method input argument`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                scalar CustomScalar
                type Query {
                    test(input: CustomScalar): Int
                }
                """)
            .scalars(customScalarType)
            .resolvers(object : GraphQLQueryResolver {
                fun test(scalar: CustomScalar) = scalar.value.length
            })
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema).build()

        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            query Test(${"$"}input: CustomScalar) {
                test(input: ${"$"}input)
            }
            """)
            .variables(mapOf("input" to "FooBar"))
            .context(Object())
            .root(Object()))

        Assert.assertEquals(6, result.getData<Map<String, Any>>()["test"])
    }

    @Test
    fun `should handle lists of scalar types`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                scalar CustomScalar
                type Query {
                    test(input: [CustomScalar]): Int
                }
                """)
            .scalars(customScalarType)
            .resolvers(object : GraphQLQueryResolver {
                fun test(scalars: List<CustomScalar>) = scalars.map { it.value.length }.sum()
            })
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema).build()

        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            query Test(${"$"}input: [CustomScalar]) {
                test(input: ${"$"}input)
            }
            """)
            .variables(mapOf("input" to listOf("Foo", "Bar")))
            .context(Object())
            .root(Object()))

        Assert.assertEquals(6, result.getData<Map<String, Any>>()["test"])
    }

    @Test
    fun `should handle proxies`() {
        val invocationHandler = object : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any>): Any {
                return when (method.name) {
                    "toString" -> "Proxy$" + System.identityHashCode(this)
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> Proxy.isProxyClass(args[0].javaClass)
                    "test" -> (args[0] as List<*>).map { (it as CustomScalar).value.length }.sum()
                    else -> UnsupportedOperationException()
                }
            }
        }

        val resolver = Proxy.newProxyInstance(
            MethodFieldResolverTest::class.java.classLoader,
            arrayOf(Resolver::class.java, GraphQLQueryResolver::class.java),
            invocationHandler
        ) as GraphQLQueryResolver

        val schema = SchemaParser.newParser()
            .schemaString(
                """
                scalar CustomScalar
                type Query {
                    test(input: [CustomScalar]): Int
                }
                """)
            .scalars(customScalarType)
            .resolvers(resolver)
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema).build()

        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            query Test(${"$"}input: [CustomScalar]) {
                test(input: ${"$"}input)
            }
            """)
            .variables(mapOf("input" to listOf("Foo", "Bar")))
            .context(Object())
            .root(Object()))

        Assert.assertEquals(6, result.getData<Map<String, Any>>()["test"])
    }

    /**
     * Custom Scalar Class type that doesn't work with Jackson serialization/deserialization
     */
    class CustomScalar private constructor(private val internalValue: String) {
        val value get() = internalValue

        companion object {
            fun of(input: Any?) = when (input) {
                is String -> CustomScalar(input)
                else -> null
            }
        }
    }

    interface Resolver {
        fun test(scalars: List<CustomScalar>): Int
    }

    private val customScalarType: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("CustomScalar")
        .description("customScalar")
        .coercing(object : Coercing<CustomScalar, String> {

            override fun parseValue(input: Any?) = CustomScalar.of(input)

            override fun parseLiteral(input: Any?) = when (input) {
                is StringValue -> CustomScalar.of(input.value)
                else -> null
            }

            override fun serialize(dataFetcherResult: Any?) = when (dataFetcherResult) {
                is CustomScalar -> dataFetcherResult.value
                else -> null
            }
        })
        .build()
}
