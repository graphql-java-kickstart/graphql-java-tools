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
import graphql.kickstart.tools.util.unwrap
import graphql.language.AstPrinter
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.Type
import graphql.language.TypeName
import graphql.schema.Coercing
import graphql.schema.CoercingSerializeException
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
    fun `fails a list of futures graphql-java can't wait on`() = assertFails("listOfPlainFutures", cantWaitOn("listOfPlainFutures"))

    @Test
    fun `fails a list of futures graphql-java can't wait on typed as Object`() =
        assertFails("listOfPlainFuturesOfAny", cantWaitOn("listOfPlainFuturesOfAny"), selection = "{ ... on Payload { data } }")

    @Test
    fun `fails a list of wrappers whose transformer returns a future graphql-java can't wait on`() = assertFails("listOfLater", cantWaitOn("listOfLater"))

    @Test
    fun `fails a list of futures graphql-java can't wait on from a suspend function`() = assertFails(
        "suspendListOfPlainFutures",
        "Suspend function '${Query::class.java.name}.suspendListOfPlainFutures' resolved to a ${FutureTask::class.java.name}, which graphql-java can't wait on. Return its value instead."
    )

    @Test
    fun `hands a future graphql-java can't wait on to its own wrapper`() = assertResolves("listOfWrappedPending", ab)

    @Test
    fun `unwraps the wrappers a schemaWrapper introduces`() = assertResolves("batchOfPayloads", ab)

    @Test
    fun `picks a nested wrapper by the type the schema was matched to`() = assertResolves("listOfBoxes", ab)

    @Test
    fun `gives a custom scalar the unwrapped values`() {
        val json = mutableListOf<Any?>()
        val schema = schema(Query(), jsonScalar(json))

        assertEquals(execute("json", schema = schema, selection = "").errors, listOf<GraphQLError>())
        assertEquals(execute("futureJson", schema = schema, selection = "").errors, listOf<GraphQLError>())
        assertEquals(execute("jsonFutures", schema = schema, selection = "").errors, listOf<GraphQLError>())
        assertEquals(json, listOf(mapOf("a" to 1), mapOf("b" to 2), mapOf("c" to 3), mapOf("d" to 4), mapOf("e" to 5)))
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
        val matcher = TypeClassMatcher(mapOf("Payload" to ObjectTypeDefinition("Payload"), "Leaf" to ScalarTypeDefinition("Leaf")))
        val method = Query::class.java.getMethod("listOfWrapped")
        val graphQLTypes = listOf(TypeName("Payload"), TypeName("Leaf")).flatMap { listOf(it, NonNullType(it), ListType(it), NonNullType(ListType(NonNullType(it))), ListType(ListType(it))) }
        val containers = listOf(List::class.java, CompletableFuture::class.java, Try::class.java, Page::class.java, DataFetcherResult::class.java, Optional::class.java, Future::class.java)
        val shapes = (1..4).fold(listOf(listOf<Class<*>>())) { shapes, _ -> shapes + shapes.filter { it.size == shapes.last().size }.flatMap { shape -> containers.map { shape + it } } }

        val accepted = graphQLTypes.flatMap { graphQLType ->
            shapes.flatMap { shape -> listOf(false, true).map { shape to javaType(shape, leaf(graphQLType).javaClass, wildcardElements = it) } }.mapNotNull { (shape, javaType) ->
                val potentialMatch = TypeClassMatcher.PotentialMatch.returnValue(graphQLType, javaType, typeResolver, ScanDictionary.ReturnValueReference(method))
                val match = try {
                    matcher.match(potentialMatch)
                } catch (e: SchemaClassScannerError) {
                    null
                }
                (shape to potentialMatch).takeIf { match != null && potentialMatch.unwrapping.javaType == leaf(graphQLType).javaClass }
            }
        }
        val failures = accepted.mapNotNull { (shape, potentialMatch) ->
            val graphQLType = potentialMatch.graphQLType
            val unwrapper = ReturnValueUnwrapper.create(
                method, potentialMatch.unwrapping, TypeUtils.getRawType(typeResolver.resolve(potentialMatch.javaType), null), isSuspendFunction = false, isSubscription = false, options.genericWrappers
            )
            val value = value(shape, leaf(graphQLType))
            val result = GraphQL.newGraphQL(fieldSchema(graphQLType) { environment -> unwrapper.unwrap(value) { environment } }).build()
                .execute(if (leaf(graphQLType) is Payload) "query { field { data } }" else "query { field }")
            val resolved = if (Future::class.java in shape) {
                result.errors.singleOrNull()?.message.orEmpty().contains("which graphql-java can't wait on")
            } else {
                result.errors.isEmpty() && result.getData<Any>() == mapOf("field" to expected(graphQLType))
            }
            "${TypeUtils.toString(potentialMatch.javaType)} as ${AstPrinter.printAst(graphQLType)}: ${result.errors.map { it.message }} ${result.getData<Any>()}".takeUnless { resolved }
        }

        assertTrue(accepted.count { (_, potentialMatch) -> leaf(potentialMatch.graphQLType) is FutureLeaf } > graphQLTypes.size)
        assertEquals(failures, listOf<String>())
    }

    private fun leaf(graphQLType: Type<*>): Any = if (graphQLType.unwrap().name == "Leaf") FutureLeaf("a") else Payload("a")

    private fun javaType(shape: List<Class<*>>, leafClass: Class<*>, wildcardElements: Boolean): java.lang.reflect.Type =
        shape.foldRight(leafClass as java.lang.reflect.Type) { container, inner ->
            val element = if (wildcardElements && container == List::class.java) TypeUtils.wildcardType().withUpperBounds(inner).build() else inner
            ParameterizedTypeImpl(container, arrayOf(element), null)
        }

    private fun value(shape: List<Class<*>>, leaf: Any): Any = shape.foldRight(leaf) { container, inner ->
        when (container) {
            List::class.java -> listOf(inner, inner)
            CompletableFuture::class.java -> CompletableFuture.supplyAsync { inner }
            Try::class.java -> Try.success(inner)
            Page::class.java -> Page(listOf(inner, inner))
            DataFetcherResult::class.java -> DataFetcherResult.newResult<Any>().data(inner).build()
            Optional::class.java -> Optional.of(inner)
            else -> FutureTask { inner }.apply { run() }
        }
    }

    private fun expected(graphQLType: Type<*>): Any = when (graphQLType) {
        is ListType -> List(2) { expected(graphQLType.type) }
        is NonNullType -> expected(graphQLType.type)
        else -> if (leaf(graphQLType) is Payload) mapOf("data" to "a") else "a"
    }

    private fun fieldSchema(graphQLType: Type<*>, dataFetcher: DataFetcher<*>): GraphQLSchema = GraphQLSchema.newSchema()
        .query(GraphQLObjectType.newObject().name("Query").field(GraphQLFieldDefinition.newFieldDefinition().name("field").type(outputType(graphQLType))))
        .codeRegistry(GraphQLCodeRegistry.newCodeRegistry().dataFetcher(FieldCoordinates.coordinates("Query", "field"), dataFetcher).build())
        .build()

    private fun outputType(graphQLType: Type<*>): GraphQLOutputType = when (graphQLType) {
        is ListType -> GraphQLList.list(outputType(graphQLType.type))
        is NonNullType -> GraphQLNonNull.nonNull(outputType(graphQLType.type))
        else -> if (leaf(graphQLType) is Payload) payloadType else leafType
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
                listOfPlainFuturesOfAny: [Item]
                listOfLater: [Payload]
                suspendListOfPlainFutures: [Payload]
                listOfWrappedPending: [Payload!]!
                batchOfPayloads: [Payload!]!
                listOfBoxes: [Payload!]!
                plainList: [Payload!]!
                futureOfList: [Payload!]!
                wrappedFuture: Payload!
                ${if (scalars.isEmpty()) "" else "json: [JSON] futureJson: JSON jsonFutures: [JSON]"}
            }

            type Payload {
                data: String!
            }

            union Item = Payload

            ${if (scalars.isEmpty()) "" else "scalar JSON"}
            """)
        .resolvers(query)
        .scalars(*scalars)
        .options(SchemaParserOptions.newOptions().genericWrappers(tryWrapper, pageWrapper, batchWrapper, boxWrapper, specialBoxWrapper, laterWrapper, pendingWrapper).build())
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

    private fun cantWaitOn(field: String) =
        "Resolver method '${Query::class.java.name}.$field' resolved to a ${FutureTask::class.java.name}, which graphql-java can't wait on. " +
            "Return a CompletionStage, such as a CompletableFuture, from the method or its generic wrapper transformer instead."

    private fun assertFails(field: String, message: String, selection: String = "{ data }") {
        val result = execute(field, selection)
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

    data class FutureLeaf(val data: String) : Future<String> by completedFuture(data)

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
        fun listOfPlainFuturesOfAny(): List<Future<Any>> = listOf(FutureTask<Any> { Payload("a") }.apply { run() })
        fun listOfLater(): List<Later<Payload>> = listOf(Later(Payload("a")))
        suspend fun suspendListOfPlainFutures(): List<Future<Payload>> = listOfPlainFutures()
        fun listOfWrappedPending(): List<Try<Pending<Payload>>> = listOf(Try.success(Pending(Payload("a"))), Try.success(Pending(Payload("b"))))
        fun json(): List<Try<Map<String, Any>>> = listOf(Try.success(mapOf("a" to 1)), Try.success(mapOf("b" to 2)))
        fun futureJson(): CompletableFuture<Try<Map<String, Any>>> = completedFuture(Try.success(mapOf("c" to 3)))
        fun jsonFutures(): List<CompletableFuture<Map<String, Any>>> = listOf(completedFuture(mapOf("d" to 4)), CompletableFuture.supplyAsync { mapOf("e" to 5) })
        fun batchOfPayloads(): Batch<Payload> = Batch(listOfFutures())
        fun listOfBoxes(): List<Box<Payload>> = listOf(Box(Payload("a")), SpecialBox(Payload("b")))
    }

    class Batch<T>(val items: List<CompletableFuture<T>>)

    open class Box<T>(val content: T)

    class SpecialBox<T>(content: T) : Box<T>(content)

    class Later<T>(val value: T)

    class Pending<T>(val value: T) : Future<T> by completedFuture(value)

    companion object {
        private val tryWrapper = GenericWrapper.withTransformer(Try::class, 0, { wrapper -> wrapper.get() })
        private val pageWrapper = GenericWrapper.listCollectionWithTransformer(Page::class, 0, { page -> page.content })
        private val batchWrapper = GenericWrapper.withTransformer(Batch::class, 0, { batch -> batch.items }) { item ->
            ParameterizedTypeImpl(List::class.java, arrayOf(ParameterizedTypeImpl(CompletableFuture::class.java, arrayOf(item), null)), null)
        }
        private val boxWrapper = GenericWrapper.withTransformer(Box::class, 0, { box -> box.content })
        private val specialBoxWrapper = GenericWrapper.withTransformer(SpecialBox::class, 0, { _ -> throw IllegalStateException("picked by the value's class") })
        private val laterWrapper = GenericWrapper.withTransformer(Later::class, 0, { later -> FutureTask { later.value }.apply { run() } })
        private val pendingWrapper = GenericWrapper.withTransformer(Pending::class, 0, { pending -> completedFuture(pending.value) })
        private val payloadType = GraphQLObjectType.newObject()
            .name("Payload")
            .field(GraphQLFieldDefinition.newFieldDefinition().name("data").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
            .build()
        private val leafType = GraphQLScalarType.newScalar()
            .name("Leaf")
            .coercing(object : Coercing<FutureLeaf, String> {
                override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String =
                    (dataFetcherResult as? FutureLeaf)?.data ?: throw CoercingSerializeException("Expected a FutureLeaf but got $dataFetcherResult")
            })
            .build()
    }
}
