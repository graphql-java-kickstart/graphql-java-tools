package graphql.kickstart.tools

import graphql.ExecutionResult
import graphql.GraphQLContext
import graphql.execution.*
import graphql.execution.instrumentation.SimpleInstrumentation
import graphql.kickstart.tools.resolver.FieldResolverError
import graphql.kickstart.tools.resolver.FieldResolverScanner
import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.NonNullType
import graphql.language.TypeName
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingEnvironmentImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import org.junit.Test
import org.reactivestreams.Publisher
import org.reactivestreams.tck.TestEnvironment
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.coroutineContext

class MethodFieldResolverDataFetcherTest {

    @Test
    @ExperimentalCoroutinesApi
    fun `data fetcher executes suspend function on coroutineContext defined by options`() {
        // setup
        val suspendClass = SuspendClass()

        val resolver = createFetcher("active", suspendClass, options = suspendClass.options)

        // expect
        @Suppress("UNCHECKED_CAST")
        val future = resolver.get(createEnvironment(DataClass())) as CompletableFuture<Boolean>
        assert(future.get())
    }

    class SuspendClass : GraphQLResolver<DataClass> {
        private val dispatcher = Dispatchers.IO
        private val job = Job()

        @ExperimentalCoroutinesApi
        val options = SchemaParserOptions.Builder()
            .coroutineContext(dispatcher + job)
            .build()

        @Suppress("UNUSED_PARAMETER")
        suspend fun isActive(data: DataClass): Boolean {
            return coroutineContext[dispatcher.key] == dispatcher &&
                coroutineContext[Job] == job.children.first()
        }
    }

    @ExperimentalCoroutinesApi
    @Test
    fun `canceling subscription Publisher also cancels underlying Kotlin coroutine channel`() {
        // setup
        val doubleChannel = DoubleChannel()

        val resolver = createFetcher("onDataNameChanged", doubleChannel)

        // expect
        @Suppress("UNCHECKED_CAST")
        val publisher = resolver.get(createEnvironment(DataClass())) as Publisher<String>
        val subscriber = TestEnvironment().newManualSubscriber(publisher)

        assertEquals(subscriber.requestNextElement(), "A")

        subscriber.cancel()
        Thread.sleep(100)
        assert(doubleChannel.channel.isClosedForReceive)
    }

    class DoubleChannel : GraphQLResolver<DataClass> {
        val channel = Channel<String>(10)

        init {
            channel.trySend("A")
            channel.trySend("B")
        }

        @Suppress("UNUSED_PARAMETER")
        fun onDataNameChanged(date: DataClass): ReceiveChannel<String> {
            return channel
        }
    }

    @Test
    fun `canceling underlying Kotlin coroutine channel also cancels subscription Publisher`() {
        // setup
        val resolver = createFetcher("onDataNameChanged", OnDataNameChanged())

        // expect
        @Suppress("UNCHECKED_CAST")
        val publisher = resolver.get(createEnvironment(DataClass())) as Publisher<String>
        val subscriber = TestEnvironment().newManualSubscriber(publisher)

        assertEquals(subscriber.requestNextElement(), "A")
        subscriber.expectErrorWithMessage(IllegalStateException::class.java, "Channel error")
    }

    @Test(expected = FieldResolverError::class)
    fun `data fetcher throws exception if resolver has too many arguments`() {
        createFetcher("active", object : GraphQLQueryResolver {
            fun active(arg1: Any, arg2: Any): Boolean = true
        })
    }

    @Test(expected = FieldResolverError::class)
    fun `data fetcher throws exception if resolver has too few arguments`() {
        createFetcher("active", listOf(InputValueDefinition("doesNotExist", TypeName("Boolean"))), object : GraphQLQueryResolver {
            fun active(): Boolean = true
        })
    }

    @Test
    fun `data fetcher prioritizes methods on the resolver`() {
        val name = "Resolver Name"
        val resolver = createFetcher("name", object : GraphQLResolver<DataClass> {
            fun getName(dataClass: DataClass): String = name
        })

        assertEquals(resolver.get(createEnvironment(DataClass())), name)
    }

    @Test
    fun `data fetcher uses data class methods if no resolver method is given`() {
        val resolver = createFetcher("name", object : GraphQLResolver<DataClass> {})

        assertEquals(resolver.get(createEnvironment(DataClass())), DataClass().name)
    }

    @Test
    fun `data fetcher prioritizes methods without a prefix`() {
        val name = "correct name"
        val resolver = createFetcher("name", object : GraphQLResolver<DataClass> {
            fun getName(dataClass: DataClass): String = "in$name"
            fun name(dataClass: DataClass): String = name
        })

        assertEquals(resolver.get(createEnvironment(DataClass())), name)
    }

    @Test
    fun `data fetcher uses 'is' prefix for booleans (primitive type)`() {
        val resolver = createFetcher("active", object : GraphQLResolver<DataClass> {
            fun isActive(dataClass: DataClass): Boolean = true
            fun getActive(dataClass: DataClass): Boolean = true
        })

        assertEquals(resolver.get(createEnvironment(DataClass())), true)
    }

    @Test
    fun `data fetcher uses 'is' prefix for Booleans (Object type)`() {
        val resolver = createFetcher("active", object : GraphQLResolver<DataClass> {
            fun isActive(dataClass: DataClass): Boolean? = null
            fun getActive(dataClass: DataClass): Boolean? = null
        })

        assertEquals(resolver.get(createEnvironment(DataClass())), null)
    }

    @Test
    fun `data fetcher passes environment if method has extra argument`() {
        val resolver = createFetcher("active", object : GraphQLResolver<DataClass> {
            fun isActive(dataClass: DataClass, env: DataFetchingEnvironment): Boolean = env is DataFetchingEnvironment
        })

        assertEquals(resolver.get(createEnvironment(DataClass())), true)
    }

    @Test
    fun `data fetcher passes environment if method has extra argument even if context is specified`() {
        val context = GraphQLContext.newContext().build()
        val resolver = createFetcher("active", resolver = object : GraphQLResolver<DataClass> {
            fun isActive(dataClass: DataClass, env: DataFetchingEnvironment): Boolean = env is DataFetchingEnvironment
        })

        assertEquals(resolver.get(createEnvironment(DataClass(), context = context)), true)
    }

    @Test
    fun `data fetcher passes context if method has extra argument and context is specified`() {
        val context = GraphQLContext.newContext().build()
        val resolver = createFetcher("active", resolver = object : GraphQLResolver<DataClass> {
            fun isActive(dataClass: DataClass, ctx: GraphQLContext): Boolean {
                return ctx == context
            }
        })

        assertEquals(resolver.get(createEnvironment(DataClass(), context = context)), true)
    }

    @Test
    fun `data fetcher marshalls input object if required`() {
        val name = "correct name"
        val resolver = createFetcher("active", listOf(InputValueDefinition("input", TypeName("InputClass"))), object : GraphQLQueryResolver {
            fun active(input: InputClass): Boolean =
                input.name == name
        })

        assertEquals(resolver.get(createEnvironment(arguments = mapOf("input" to mapOf("name" to name)))), true)
    }

    @Test
    fun `data fetcher doesn't marshall input object if not required`() {
        val name = "correct name"
        val resolver = createFetcher("active", listOf(InputValueDefinition("input", TypeName("Map"))), object : GraphQLQueryResolver {
            fun active(input: Map<*, *>): Boolean =
                input["name"] == name
        })

        assertEquals(resolver.get(createEnvironment(arguments = mapOf("input" to mapOf("name" to name)))), true)
    }

    @Test
    fun `data fetcher returns null if nullable argument is passed null`() {
        val resolver = createFetcher("echo", listOf(InputValueDefinition("message", TypeName("String"))), object : GraphQLQueryResolver {
            fun echo(message: String?): String? =
                message
        })

        assertEquals(resolver.get(createEnvironment()), null)
    }

    @Test(expected = ResolverError::class)
    fun `data fetcher throws exception if non-null argument is passed null`() {
        val resolver = createFetcher("echo", listOf(InputValueDefinition("message", NonNullType(TypeName("String")))), object : GraphQLQueryResolver {
            fun echo(message: String): String =
                message
        })

        resolver.get(createEnvironment())
    }

    class OnDataNameChanged : GraphQLResolver<DataClass> {
        private val channel = Channel<String>(10)

        init {
            channel.trySend("A")
            channel.close(IllegalStateException("Channel error"))
        }

        @Suppress("UNUSED_PARAMETER")
        fun onDataNameChanged(date: DataClass): ReceiveChannel<String> {
            return channel
        }
    }

    private fun createFetcher(
        methodName: String,
        arguments: List<InputValueDefinition> = emptyList(),
        resolver: GraphQLResolver<*>
    ): DataFetcher<*> {
        return createFetcher(methodName, resolver, arguments)
    }

    private fun createFetcher(
        methodName: String,
        resolver: GraphQLResolver<*>,
        arguments: List<InputValueDefinition> = emptyList(),
        options: SchemaParserOptions = SchemaParserOptions.defaultOptions()
    ): DataFetcher<*> {
        val field = FieldDefinition.newFieldDefinition()
            .name(methodName)
            .type(TypeName("Boolean"))
            .inputValueDefinitions(arguments)
            .build()

        val resolverInfo = if (resolver is GraphQLQueryResolver) {
            RootResolverInfo(listOf(resolver), options)
        } else {
            NormalResolverInfo(resolver, options)
        }
        return FieldResolverScanner(options).findFieldResolver(field, resolverInfo).createDataFetcher()
    }

    private fun createEnvironment(source: Any = Object(), arguments: Map<String, Any> = emptyMap(), context: GraphQLContext? = null): DataFetchingEnvironment {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment(buildExecutionContext())
            .source(source)
            .arguments(arguments)
            .graphQLContext(context)
            .build()
    }

    private fun buildExecutionContext(): ExecutionContext {
        val executionStrategy = object : ExecutionStrategy() {
            override fun execute(executionContext: ExecutionContext, parameters: ExecutionStrategyParameters): CompletableFuture<ExecutionResult> {
                throw AssertionError("should not be called")
            }
        }
        val executionId = ExecutionId.from("executionId123")
        return ExecutionContextBuilder.newExecutionContextBuilder()
            .instrumentation(SimpleInstrumentation.INSTANCE)
            .executionId(executionId)
            .queryStrategy(executionStrategy)
            .mutationStrategy(executionStrategy)
            .subscriptionStrategy(executionStrategy)
            .build()
    }

    data class DataClass(val name: String = "TestName")

    class InputClass {
        var name: String? = null
    }

    class ContextClass
}
