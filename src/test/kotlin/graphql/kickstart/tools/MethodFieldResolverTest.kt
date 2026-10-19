package graphql.kickstart.tools

import graphql.ExceptionWhileDataFetching
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.GraphQLContext
import graphql.GraphQLError
import graphql.execution.CoercedVariables
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingSerializeException
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLScalarType
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
            .assertEveryFieldResolves()

        val gql = GraphQL.newGraphQL(schema).build()

        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            query {
                testValue(input: "test-value")
                testOmitted
                testNull(input: null)
            }
            """)
            .root(Any()))

        assertEquals(result.getData(), mapOf(
            "testValue" to "Optional[test-value]",
            "testOmitted" to "Optional.empty",
            "testNull" to "Optional.empty"
        ))
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
            .assertEveryFieldResolves()

        val gql = GraphQL.newGraphQL(schema).build()

        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            query {
                testValue(input: "test-value")
                testOmitted
                testNull(input: null)
            }
            """)
            .root(Any()))

        assertEquals(result.getData(), mapOf(
            "testValue" to "Optional[test-value]",
            "testOmitted" to "null",
            "testNull" to "Optional.empty"
        ))
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
                fun test(scalar: CustomScalar?) = scalar?.value?.length
            })
            .build()
            .makeExecutableSchema()
            .assertEveryFieldResolves()

        val gql = GraphQL.newGraphQL(schema).build()

        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            query Test(${"$"}input: CustomScalar) {
                test(input: ${"$"}input)
            }
            """)
            .variables(mapOf("input" to "FooBar"))
            .root(Any()))

        assertEquals(result.getData(), mapOf("test" to 6))
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
                fun test(scalars: List<CustomScalar>?) = scalars?.map { it.value.length }?.sum()
            })
            .build()
            .makeExecutableSchema()
            .assertEveryFieldResolves()

        val gql = GraphQL.newGraphQL(schema).build()

        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            query Test(${"$"}input: [CustomScalar]) {
                test(input: ${"$"}input)
            }
            """)
            .variables(mapOf("input" to listOf("Foo", "Bar")))
            .root(Any()))

        assertEquals(result.getData(), mapOf("test" to 6))
    }

    @Test
    fun `should convert nested lists of scalars to the parameter's collection type`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    count(tags: [[String]]): Int
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun count(tags: List<Set<String>>) = tags.sumOf { it.size }
            })
            .build()
            .makeExecutableSchema()

        val result = GraphQL.newGraphQL(schema).build().execute("""{ count(tags: [["a", "a", "b"]]) }""")

        assertEquals(result.errors, emptyList<GraphQLError>())
        assertEquals(result.getData(), mapOf("count" to 2))
    }

    @Test
    fun `should handle proxies`() {
        val invocationHandler = object : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any>): Any? {
                return when (method.name) {
                    "toString" -> "Proxy$" + System.identityHashCode(this)
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> Proxy.isProxyClass(args[0].javaClass)
                    "test" -> (args[0] as List<*>?)?.map { (it as CustomScalar).value.length }?.sum()
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
            .assertEveryFieldResolves()

        val gql = GraphQL.newGraphQL(schema).build()

        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            query Test(${"$"}input: [CustomScalar]) {
                test(input: ${"$"}input)
            }
            """)
            .variables(mapOf("input" to listOf("Foo", "Bar")))
            .root(Any()))

        assertEquals(result.getData(), mapOf("test" to 6))
    }

    @Test
    fun `should unwrap and rethrow resolver exceptions`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    test: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun test(): String = throw Exception("Whoops")
            })
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema).build()
        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            query {
                test
            }
            """))

        assertEquals(result.errors.size, 1)
        val exceptionWhileDataFetching = result.errors[0] as ExceptionWhileDataFetching
        assertEquals(exceptionWhileDataFetching.exception.message, "Whoops")
    }

    @Test
    fun `should return null when generic wrapper transformer returns null`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    name: String
                    names: [String!]
                    nameWithArgument(input: String): String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun name(): Maybe<String> = Maybe.None
                fun names(): Maybe<List<String>> = Maybe.None
                fun nameWithArgument(input: String?): Maybe<String> = Maybe.None
            })
            .options(SchemaParserOptions.newOptions()
                .genericWrappers(SchemaParserOptions.GenericWrapper.withTransformer(Maybe::class, 0, { maybe -> (maybe as? Maybe.Some)?.value }))
                .build())
            .build()
            .makeExecutableSchema()
            .assertEveryFieldResolves()

        val gql = GraphQL.newGraphQL(schema).build()
        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            query {
                name
                names
                nameWithArgument(input: "test")
            }
            """))

        assertEquals(result.errors, listOf())
        assertEquals(result.getData(), mapOf(
            "name" to null,
            "names" to null,
            "nameWithArgument" to null
        ))
    }

    @Test
    fun `should warn when a resolver method is missing a parameter for an argument`() {
        val (_, warnings) = withWarnings {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        user(id: ID!, active: Boolean!): String
                        users(active: Boolean, first: Int): String
                        image: Image
                        search(filter: Filter): String
                        count(active: Boolean): String
                    }

                    type Image {
                        thumbnail(size: Int): String
                    }

                    input Filter {
                        name: String
                    }
                    """)
                .resolvers(MissingParameterQuery(), ImageResolver())
                .options(SchemaParserOptions.newOptions().contextClass(Map::class).build())
                .build()
                .makeExecutableSchema()
        }

        val query = MissingParameterQuery::class.java.name
        assertEquals(warnings, listOf(
            "Field 'Query.user' has no parameter for its argument 'active: Boolean!' in $query.user, so the Map parameter gets the " +
                "argument instead. Add a parameter for each argument, in order, before the Map parameter.",
            "Field 'Query.users' has no parameter for its argument 'active: Boolean' in $query.users, so the GraphQLContext parameter " +
                "gets the argument instead. Add a parameter for each argument, in order, before the GraphQLContext parameter.",
            "Field 'Image.thumbnail' has no parameter for its argument 'size: Int' in ${ImageResolver::class.java.name}.thumbnail, so the " +
                "DataFetchingEnvironment parameter gets the argument instead. Add a parameter for each argument, in order, before the " +
                "DataFetchingEnvironment parameter."
        ))
    }

    class MissingParameterQuery : GraphQLQueryResolver {
        fun user(id: String, context: Map<String, Any>) = "$id $context"
        fun users(context: GraphQLContext?, first: Int?) = "$context $first"
        fun image() = Image()
        fun search(filter: Map<String, Any>?) = filter.toString()
        fun count(active: Boolean?, context: Map<String, Any>) = "$active $context"
    }

    class Image

    class ImageResolver : GraphQLResolver<Image> {
        fun thumbnail(image: Image, env: DataFetchingEnvironment?) = env.toString()
    }

    sealed class Maybe<out T> {
        data class Some<T>(val value: T) : Maybe<T>()
        object None : Maybe<Nothing>()
    }

    /**
     * Custom Scalar Class type that doesn't work with Jackson serialization/deserialization
     */
    class CustomScalar private constructor(private val internalValue: String) {
        val value get() = internalValue

        companion object {
            fun of(input: Any?) = when (input) {
                is String -> CustomScalar(input)
                else -> throw IllegalArgumentException()
            }
        }
    }

    interface Resolver {
        fun test(scalars: List<CustomScalar>?): Int?
    }

    private val customScalarType: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("CustomScalar")
        .description("customScalar")
        .coercing(object : Coercing<CustomScalar, String> {

            override fun parseValue(input: Any, context: GraphQLContext, locale: Locale) = CustomScalar.of(input)

            override fun parseLiteral(input: Value<*>, variables: CoercedVariables, context: GraphQLContext, locale: Locale): CustomScalar = when (input) {
                is StringValue -> CustomScalar.of(input.value)
                else -> throw CoercingParseLiteralException()
            }

            override fun serialize(dataFetcherResult: Any, context: GraphQLContext, locale: Locale) = when (dataFetcherResult) {
                is CustomScalar -> dataFetcherResult.value
                else -> throw CoercingSerializeException()
            }
        })
        .build()
}
