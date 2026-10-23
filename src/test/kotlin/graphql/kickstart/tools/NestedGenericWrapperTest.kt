package graphql.kickstart.tools

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.GraphQLContext
import graphql.GraphQLError
import graphql.execution.DataFetcherResult
import graphql.Scalars
import graphql.execution.DefaultValueUnboxer
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper
import graphql.kickstart.tools.resolver.ReturnValueUnwrapper
import graphql.kickstart.tools.util.ParameterizedTypeImpl
import graphql.language.AstPrinter
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.Type
import graphql.language.TypeName
import graphql.schema.Coercing
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import org.dataloader.BatchLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderRegistry
import org.apache.commons.lang3.reflect.TypeUtils
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

class NestedGenericWrapperTest {

    private val schema = schema(Query())

    private val ab = listOf(mapOf("data" to "a"), mapOf("data" to "b"))

    @Test
    fun `unwraps wrappers in a list`() = assertResolves("listOfWrapped", ab)

    @Test
    fun `waits on futures in a list`() = assertResolves("listOfFutures", ab)

    @Test
    fun `unwraps wrappers in a list in a future`() = assertResolves("futureOfListOfWrapped", ab)

    @Test
    fun `unwraps scalar wrappers in a list`() = assertResolves("listOfWrappedStrings", listOf("a", "b"), selection = "")

    @Test
    fun `waits on scalar futures in a list`() = assertResolves("listOfFutureStrings", listOf("a", "b"), selection = "")

    @Test
    fun `keeps the order of futures that complete in another order`() = assertResolves("futuresCompletingInReverse", listOf("1", "2", "3"), selection = "")

    @Test
    fun `unwraps wrappers in nested lists`() = assertResolves("listOfListsOfWrapped", listOf(listOf(mapOf("data" to "a")), listOf(mapOf("data" to "b"))))

    @Test
    fun `unwraps wrappers in a list in a wrapper`() = assertResolves("wrappedListOfWrapped", ab)

    @Test
    fun `unwraps wrappers in a list collection wrapper`() = assertResolves("pageOfWrapped", ab)

    @Test
    fun `unwraps wrappers in a list in a DataFetcherResult and keeps its errors`() {
        val result = execute("resultOfListOfWrapped")

        assertEquals(result.errors.map { it.message }, listOf("partial"))
        assertEquals(result.getData(), mapOf("resultOfListOfWrapped" to ab))
    }

    @Test
    fun `unwraps wrappers in DataFetcherResults in a list`() = assertResolves("listOfResultsOfWrapped", ab)

    @Test
    fun `unwraps wrappers in a list in an Optional`() = assertResolves("optionalListOfWrapped", ab)

    @Test
    fun `unwraps wrappers in a list from a suspend function`() = assertResolves("suspendListOfWrapped", ab)

    @Test
    fun `waits on futures in a list from a suspend function`() = assertResolves("suspendListOfFutures", ab)

    @Test
    fun `waits on futures in a list of nullable elements from a suspend function`() = assertResolves("suspendNullableListOfFutures", ab)

    @Test
    fun `resolves a list of scalars typed by a suspend function's own type variable`() = assertResolves("suspendListOfTypeVariable", listOf("a", "b"), selection = "")

    @Test
    fun `keeps null elements and null transformer results in a list`() = assertResolves("listOfNullableWrapped", listOf(mapOf("data" to "a"), null, null))

    @Test
    fun `waits on a future in a future`() = assertResolves("futureOfFuture", mapOf("data" to "a"))

    @Test
    fun `waits on a future in an Optional`() = assertResolves("optionalFuture", mapOf("data" to "a"))

    @Test
    fun `waits on futures in wrappers in a list`() = assertResolves("listOfWrappedFutures", ab)

    @Test
    fun `waits on DataLoader futures in a list`() = assertResolves("loadedPayloads", ab)

    @Test
    fun `fails the list when one of its futures fails`() = assertFails("listWithFailedFuture", "failed future")

    @Test
    fun `fails the list when one of its wrappers fails`() = assertFails("listWithFailedWrapper", "failed wrapper")

    @Test
    fun `fails the list when one of its wrappers in a future fails`() = assertFails("futureOfListWithFailedWrapper", "failed wrapper")

    @Test
    fun `leaves futures in a list that graphql-java can't wait on as they are`() {
        val result = execute("listOfPlainFutures")

        assertEquals(result.errors.map { it.message }, listOf(
            "Exception while fetching data (/listOfPlainFutures[0]/data) : " +
                "Expected source object to be an instance of '${Payload::class.java.name}' but instead got '${FutureTask::class.java.name}'"
        ))
    }

    @Test
    fun `gives a custom scalar the wrappers as they are`() {
        val json = mutableListOf<Any?>()
        val schema = schema(Query(), jsonScalar(json))

        assertEquals(execute("json", schema = schema, selection = "").errors, listOf<GraphQLError>())
        assertEquals(execute("futureJson", schema = schema, selection = "").errors, listOf<GraphQLError>())
        assertEquals(json.map { it?.javaClass }, listOf(Try::class.java, Try::class.java, Try::class.java))
    }

    @Test
    fun `returns the resolver's own value when nothing is nested`() {
        val query = Query()
        val schema = schema(query)

        assertSame(fetch(schema, "plainList"), query.plainList)
        assertSame(fetch(schema, "futureOfList"), query.futureOfList)
        assertSame(fetch(schema, "wrappedFuture"), query.wrappedFuture.get())
    }

    @Test
    fun `gives a ValueUnboxer the unwrapped values`() {
        val gql = GraphQL.newGraphQL(schema)
            .valueUnboxer { value -> (if (value is Try<*>) value.get() else value)?.let { DefaultValueUnboxer.unboxValue(it) } }
            .build()

        assertEquals(execute("listOfWrapped", gql = gql).getData(), mapOf("listOfWrapped" to ab))
        assertEquals(execute("listOfWrappedStrings", gql = gql, selection = "").getData(), mapOf("listOfWrappedStrings" to listOf("a", "b")))
        assertEquals(execute("listWithFailedWrapper", gql = gql).errors.map { it.message }, listOf("Exception while fetching data (/listWithFailedWrapper) : failed wrapper"))
    }

    @Test
    fun `resolves every return type the matcher accepts`() {
        val options = SchemaParserOptions.newOptions().genericWrappers(tryWrapper, pageWrapper).build()
        val typeResolver = GenericTypeResolver(Query::class.java, options)
        val matcher = TypeClassMatcher(mapOf("Payload" to ObjectTypeDefinition("Payload")))
        val method = Query::class.java.getMethod("listOfWrapped")
        val payload = TypeName("Payload")
        val graphQLTypes = listOf(payload, NonNullType(payload), ListType(payload), NonNullType(ListType(NonNullType(payload))), ListType(ListType(payload)))
        val containers = listOf(List::class.java, CompletableFuture::class.java, Try::class.java, Page::class.java, DataFetcherResult::class.java, Optional::class.java)
        val shapes = (1..4).fold(listOf(listOf<Class<*>>())) { shapes, _ -> shapes + shapes.filter { it.size == shapes.last().size }.flatMap { shape -> containers.map { shape + it } } }
        val javaTypes = shapes.flatMap { shape -> listOf(shape to javaType(shape, wildcardElements = false), shape to javaType(shape, wildcardElements = true)) }

        val accepted = javaTypes.flatMap { (shape, javaType) -> graphQLTypes.map { Triple(shape, javaType, it) } }.filter { (_, javaType, graphQLType) ->
            val match = try {
                matcher.match(TypeClassMatcher.PotentialMatch.returnValue(graphQLType, typeResolver.resolve(javaType), typeResolver, ScanDictionary.ReturnValueReference(method)))
            } catch (e: SchemaClassScannerError) {
                null
            }
            (match as? TypeClassMatcher.ValidMatch)?.javaType == Payload::class.java
        }
        val failures = accepted.mapNotNull { (shape, javaType, graphQLType) ->
            val unwrapper = ReturnValueUnwrapper.create(
                method, javaType, TypeUtils.getRawType(typeResolver.resolve(javaType), null), graphQLType, false, typeResolver, options.genericWrappers, emptySet()
            )
            val result = GraphQL.newGraphQL(fieldSchema(graphQLType) { environment -> unwrapper.unwrap(value(shape)) { environment } }).build().execute("query { field { data } }")
            "${TypeUtils.toString(javaType)} as ${AstPrinter.printAst(graphQLType)}: ${result.errors.map { it.message }} ${result.getData<Any>()}"
                .takeUnless { result.errors.isEmpty() && result.getData<Any>() == mapOf("field" to expected(graphQLType)) }
        }

        assertTrue(accepted.size > graphQLTypes.size)
        assertEquals(failures, listOf<String>())
    }

    private fun javaType(shape: List<Class<*>>, wildcardElements: Boolean): java.lang.reflect.Type =
        shape.foldRight(Payload::class.java as java.lang.reflect.Type) { container, inner ->
            val element = if (wildcardElements && container == List::class.java) TypeUtils.wildcardType().withUpperBounds(inner).build() else inner
            ParameterizedTypeImpl(container, arrayOf(element), null)
        }

    private fun value(shape: List<Class<*>>): Any = shape.foldRight(Payload("a") as Any) { container, inner ->
        when (container) {
            List::class.java -> listOf(inner, inner)
            CompletableFuture::class.java -> CompletableFuture.supplyAsync { inner }
            Try::class.java -> Try.success(inner)
            Page::class.java -> Page(listOf(inner, inner))
            DataFetcherResult::class.java -> DataFetcherResult.newResult<Any>().data(inner).build()
            else -> Optional.of(inner)
        }
    }

    private fun expected(graphQLType: Type<*>): Any = when (graphQLType) {
        is ListType -> List(2) { expected(graphQLType.type) }
        is NonNullType -> expected(graphQLType.type)
        else -> mapOf("data" to "a")
    }

    private fun fieldSchema(graphQLType: Type<*>, dataFetcher: DataFetcher<*>): GraphQLSchema = GraphQLSchema.newSchema()
        .query(GraphQLObjectType.newObject().name("Query").field(GraphQLFieldDefinition.newFieldDefinition().name("field").type(outputType(graphQLType))))
        .codeRegistry(GraphQLCodeRegistry.newCodeRegistry().dataFetcher(FieldCoordinates.coordinates("Query", "field"), dataFetcher).build())
        .build()

    private fun outputType(graphQLType: Type<*>): GraphQLOutputType = when (graphQLType) {
        is ListType -> GraphQLList.list(outputType(graphQLType.type))
        is NonNullType -> GraphQLNonNull.nonNull(outputType(graphQLType.type))
        else -> payloadType
    }

    private fun schema(query: Query, vararg scalars: GraphQLScalarType): GraphQLSchema = SchemaParser.newParser()
        .schemaString(
            """
            type Query {
                listOfWrapped: [Payload!]!
                listOfFutures: [Payload!]!
                futureOfListOfWrapped: [Payload!]!
                listOfWrappedStrings: [String!]!
                listOfFutureStrings: [String]
                futuresCompletingInReverse: [String!]!
                listOfListsOfWrapped: [[Payload!]!]!
                wrappedListOfWrapped: [Payload!]!
                pageOfWrapped: [Payload!]!
                resultOfListOfWrapped: [Payload!]!
                listOfResultsOfWrapped: [Payload!]!
                optionalListOfWrapped: [Payload!]
                suspendListOfWrapped: [Payload!]!
                suspendListOfFutures: [Payload!]!
                suspendNullableListOfFutures: [Payload]
                suspendListOfTypeVariable: [String]
                listOfNullableWrapped: [Payload]!
                futureOfFuture: Payload!
                optionalFuture: Payload
                listOfWrappedFutures: [Payload!]!
                loadedPayloads: [Payload!]!
                listWithFailedFuture: [Payload!]
                listWithFailedWrapper: [Payload]
                futureOfListWithFailedWrapper: [Payload]
                listOfPlainFutures: [Payload]
                plainList: [Payload!]!
                futureOfList: [Payload!]!
                wrappedFuture: Payload!
                ${if (scalars.isEmpty()) "" else "json: [JSON] futureJson: JSON"}
            }

            type Payload {
                data: String!
            }

            ${if (scalars.isEmpty()) "" else "scalar JSON"}
            """)
        .resolvers(query)
        .scalars(*scalars)
        .options(SchemaParserOptions.newOptions().genericWrappers(tryWrapper, pageWrapper).build())
        .build()
        .makeExecutableSchema()

    private fun execute(field: String, selection: String = "{ data }", schema: GraphQLSchema = this.schema, gql: GraphQL = GraphQL.newGraphQL(schema).build()): ExecutionResult {
        val registry = DataLoaderRegistry().apply {
            register("payloads", DataLoaderFactory.newDataLoader(BatchLoader<String, Payload> { keys -> completedFuture(keys.map { Payload(it) }) }))
        }
        return gql.execute(ExecutionInput.newExecutionInput().query("query { $field $selection }").dataLoaderRegistry(registry))
    }

    private fun assertResolves(field: String, expected: Any?, selection: String = "{ data }") {
        val result = execute(field, selection)
        assertEquals(result.errors, listOf<GraphQLError>())
        assertEquals(result.getData(), mapOf(field to expected))
    }

    private fun assertFails(field: String, message: String) {
        val result = execute(field)
        assertEquals(result.errors.map { it.message }, listOf("Exception while fetching data (/$field) : $message"))
        assertEquals(result.getData(), mapOf(field to null))
    }

    private fun fetch(schema: GraphQLSchema, field: String): Any? {
        val definition = schema.queryType.getFieldDefinition(field)
        val environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().fieldDefinition(definition).build()
        return schema.codeRegistry.getDataFetcher(FieldCoordinates.coordinates("Query", field), definition).get(environment)
    }

    private fun jsonScalar(serialized: MutableList<Any?>): GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("JSON")
        .coercing(object : Coercing<Any, Any> {
            override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): Any = dataFetcherResult.also { serialized += it }
        })
        .build()

    class Try<out T> private constructor(private val value: T?, private val failure: Throwable?) {
        fun get(): T? = if (failure != null) throw failure else value

        companion object {
            fun <T> success(value: T?) = Try(value, null)
            fun <T> failure(message: String) = Try<T>(null, IllegalStateException(message))
        }
    }

    class Page<T>(val content: List<T>)

    data class Payload(val data: String)

    class Query : GraphQLQueryResolver {
        val plainList = listOf(Payload("a"))
        val futureOfList: CompletableFuture<List<Payload>> = completedFuture(plainList)
        val wrappedFuture: Try<CompletableFuture<Payload>> = Try.success(completedFuture(Payload("a")))

        private fun <T : Any> result(data: T, vararg errors: GraphQLError): DataFetcherResult<T> = DataFetcherResult.newResult<T>().data(data).errors(errors.toList()).build()

        fun listOfWrapped(): List<Try<Payload>> = listOf(Try.success(Payload("a")), Try.success(Payload("b")))
        fun listOfFutures(): List<CompletableFuture<Payload>> = listOf(completedFuture(Payload("a")), CompletableFuture.supplyAsync { Payload("b") })
        fun futureOfListOfWrapped(): CompletableFuture<List<Try<Payload>>> = CompletableFuture.supplyAsync { listOfWrapped() }
        fun listOfWrappedStrings(): List<Try<String>> = listOf(Try.success("a"), Try.success("b"))
        fun listOfFutureStrings(): List<CompletableFuture<String>> = listOf(completedFuture("a"), CompletableFuture.supplyAsync { "b" })
        fun futuresCompletingInReverse(): List<CompletableFuture<String>> {
            val futures = List(3) { CompletableFuture<String>() }
            Thread { futures.indices.reversed().forEach { futures[it].complete("${it + 1}") } }.start()
            return futures
        }
        fun listOfListsOfWrapped(): List<List<Try<Payload>>> = listOf(listOf(Try.success(Payload("a"))), listOf(Try.success(Payload("b"))))
        fun wrappedListOfWrapped(): Try<List<Try<Payload>>> = Try.success(listOfWrapped())
        fun pageOfWrapped(): Page<Try<Payload>> = Page(listOfWrapped())
        fun resultOfListOfWrapped(): DataFetcherResult<List<Try<Payload>>> = result(listOfWrapped(), GraphQLError.newError().message("partial").build())
        fun listOfResultsOfWrapped(): List<DataFetcherResult<Try<Payload>>> = listOfWrapped().map { result(it) }
        fun optionalListOfWrapped(): Optional<List<Try<Payload>>> = Optional.of(listOfWrapped())
        suspend fun suspendListOfWrapped(): List<Try<Payload>> = listOfWrapped()
        suspend fun suspendListOfFutures(): List<CompletableFuture<Payload>> = listOfFutures()
        suspend fun suspendNullableListOfFutures(): List<CompletableFuture<Payload>> = listOfFutures()
        @Suppress("UNCHECKED_CAST")
        suspend fun <T> suspendListOfTypeVariable(): List<T> = listOf("a", "b") as List<T>
        fun listOfNullableWrapped(): List<Try<Payload>?> = listOf(Try.success(Payload("a")), Try.success(null), null)
        fun futureOfFuture(): CompletableFuture<CompletableFuture<Payload>> = CompletableFuture.supplyAsync { completedFuture(Payload("a")) }
        fun optionalFuture(): Optional<CompletableFuture<Payload>> = Optional.of(CompletableFuture.supplyAsync { Payload("a") })
        fun listOfWrappedFutures(): List<Try<CompletableFuture<Payload>>> = listOfFutures().map { Try.success(it) }
        fun loadedPayloads(env: DataFetchingEnvironment): List<CompletableFuture<Payload>> =
            listOf("a", "b").map { env.getDataLoader<String, Payload>("payloads")!!.load(it) }
        fun listWithFailedFuture(): List<CompletableFuture<Payload>> = listOf(completedFuture(Payload("a")), CompletableFuture.failedFuture(IllegalStateException("failed future")))
        fun listWithFailedWrapper(): List<Try<Payload>> = listOf(Try.success(Payload("a")), Try.failure("failed wrapper"))
        fun futureOfListWithFailedWrapper(): CompletableFuture<List<Try<Payload>>> = CompletableFuture.supplyAsync { listWithFailedWrapper() }
        fun listOfPlainFutures(): List<Future<Payload>> = listOf(FutureTask { Payload("a") }.apply { run() })
        fun json(): List<Try<Map<String, Any>>> = listOf(Try.success(mapOf("a" to 1)), Try.success(mapOf("b" to 2)))
        fun futureJson(): CompletableFuture<Try<Map<String, Any>>> = completedFuture(Try.success(mapOf("c" to 3)))
    }

    companion object {
        private val tryWrapper = GenericWrapper.withTransformer(Try::class, 0, { wrapper -> wrapper.get() })
        private val pageWrapper = GenericWrapper.listCollectionWithTransformer(Page::class, 0, { page -> page.content })
        private val payloadType = GraphQLObjectType.newObject()
            .name("Payload")
            .field(GraphQLFieldDefinition.newFieldDefinition().name("data").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
            .build()
    }
}
