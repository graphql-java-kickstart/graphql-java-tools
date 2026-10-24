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
import graphql.kickstart.tools.resolver.FieldResolverError
import graphql.kickstart.tools.resolver.MissingResolverDataFetcherProvider
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLScalarType
import org.junit.Assert.assertThrows
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
    fun `should handle nested lists of scalar types`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                scalar CustomScalar
                type Query {
                    count(tags: [[String]]): Int
                    test(input: [[CustomScalar]]): Int
                }
                """)
            .scalars(customScalarType)
            .resolvers(object : GraphQLQueryResolver {
                fun count(tags: List<Set<String>>) = tags.sumOf { it.size }
                fun test(scalars: List<Set<CustomScalar>>) = scalars.sumOf { set -> set.sumOf { it.value.length } }
            })
            .build()
            .makeExecutableSchema()

        val result = GraphQL.newGraphQL(schema).build().execute("""{ count(tags: [["a", "a", "b"]]) test(input: [["Foo"], ["Bar"]]) }""")

        assertEquals(result.errors, emptyList<GraphQLError>())
        assertEquals(result.getData(), mapOf("count" to 2, "test" to 6))
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
    fun `should fail the build when a resolver method is missing a parameter for an argument`() {
        val query = MissingParameterQuery::class.java.name
        val messagesByQueryField = mapOf(
            "user(id: ID!, active: Boolean!): String" to
                "Field 'Query.user' has no parameter for its argument 'active: Boolean!' in $query.user, so the Map parameter gets the " +
                "argument instead. Add a parameter for each argument, in order, before the Map parameter.",
            "users(active: Boolean, first: Int): String" to
                "Field 'Query.users' has no parameter for its argument 'active: Boolean' in $query.users, so the GraphQLContext parameter " +
                "gets the argument instead. Add a parameter for each argument, in order, before the GraphQLContext parameter.",
            "image: Image" to
                "Field 'Image.thumbnail' has no parameter for its argument 'size: Int' in ${ImageResolver::class.java.name}.thumbnail, so " +
                "the DataFetchingEnvironment parameter gets the argument instead. Add a parameter for each argument, in order, before the " +
                "DataFetchingEnvironment parameter."
        )

        messagesByQueryField.forEach { (queryField, message) ->
            val error = assertThrows(FieldResolverError::class.java) {
                buildMissingParameterSchema(queryField, SchemaParserOptions.newOptions())
            }

            assertEquals(error.message, message)
        }
    }

    @Test
    fun `should fail the build for a missing parameter when unimplemented resolvers are allowed`() {
        val options = listOf(
            SchemaParserOptions.newOptions().allowUnimplementedResolvers(true),
            SchemaParserOptions.newOptions().missingResolverDataFetcherProvider(MissingResolverDataFetcherProvider { _, _ -> DataFetcher { "provided" } })
        )

        options.forEach {
            val error = assertThrows(FieldResolverError::class.java) {
                buildMissingParameterSchema("users(active: Boolean, first: Int): String", it)
            }

            assert("Field 'Query.users' has no parameter for its argument 'active: Boolean'" in error.message!!) { error.message!! }
        }
    }

    @Test
    fun `should build when the context class is an argument type`() {
        val schema = buildMissingParameterSchema(
            "search(filter: Filter): String\ncount(active: Boolean): String",
            SchemaParserOptions.newOptions()
        )

        assertEquals(schema.queryType.fieldDefinitions.map { it.name }, listOf("search", "count"))
    }

    private fun buildMissingParameterSchema(queryField: String, options: SchemaParserOptions.Builder) =
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    $queryField
                }

                type Image {
                    thumbnail(size: Int): String
                }

                input Filter {
                    name: String
                }
                """)
            .resolvers(MissingParameterQuery(), ImageResolver())
            .options(options.contextClass(Map::class).build())
            .build()
            .makeExecutableSchema()

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

    @Test
    fun `should pass the source to a resolver method declared by the data class itself`() {
        val schema = SchemaParser.newParser()
            .schemaString("type Query { badge: Badge! } type Badge { size(scale: Int!): Int! }")
            .resolvers(BadgeQuery(), Badge("resolver"))
            .build()
            .makeExecutableSchema()

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) { "{ badge { size(scale: 3) } }" }

        assertEquals(data, mapOf("badge" to mapOf("size" to 15)))
    }

    class BadgeQuery : GraphQLQueryResolver {
        fun badge() = Badge("badge")
    }

    class Badge(val label: String) : GraphQLResolver<Badge> {
        fun size(badge: Badge, scale: Int) = badge.label.length * scale
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
