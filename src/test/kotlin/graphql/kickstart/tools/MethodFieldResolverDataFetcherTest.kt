package graphql.kickstart.tools

import graphql.ExecutionResult
import graphql.execution.*
import graphql.execution.instrumentation.SimpleInstrumentation
import graphql.kickstart.tools.scanner.FieldResolverScanner
import graphql.kickstart.tools.scanner.NormalResolverInfo
import graphql.kickstart.tools.scanner.RootResolverInfo
import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.TypeName
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingEnvironmentImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import org.junit.Assert
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
        Assert.assertTrue(future.get())
    }

    class SuspendClass : GraphQLResolver<DataClass> {
        val dispatcher = Dispatchers.IO
        val job = Job()

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

        Assert.assertEquals("A", subscriber.requestNextElement())

        subscriber.cancel()
        Thread.sleep(100)
        Assert.assertTrue(doubleChannel.channel.isClosedForReceive)
    }

    class DoubleChannel : GraphQLResolver<DataClass> {
        val channel = Channel<String>(10)

        init {
            channel.offer("A")
            channel.offer("B")
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

        Assert.assertEquals("A", subscriber.requestNextElement())
        subscriber.expectErrorWithMessage(IllegalStateException::class.java, "Channel error")
    }

    class OnDataNameChanged : GraphQLResolver<DataClass> {
        val channel = Channel<String>(10)

        init {
            channel.offer("A")
            channel.close(IllegalStateException("Channel error"))
        }

        @Suppress("UNUSED_PARAMETER")
        fun onDataNameChanged(date: DataClass): ReceiveChannel<String> {
            return channel
        }
    }

    private fun createFetcher(
        methodName: String,
        resolver: GraphQLResolver<*>,
        arguments: List<InputValueDefinition> = emptyList(),
        options: SchemaParserOptions = SchemaParserOptions.defaultOptions()
    ): DataFetcher<*> {
        val field = FieldDefinition(methodName, TypeName("Boolean")).apply { inputValueDefinitions.addAll(arguments) }
        val resolverInfo = if (resolver is GraphQLQueryResolver) {
            RootResolverInfo(listOf(resolver), options)
        } else {
            NormalResolverInfo(resolver, options)
        }
        return FieldResolverScanner(options).findFieldResolver(field, resolverInfo).createDataFetcher()
    }

    private fun createEnvironment(source: Any, arguments: Map<String, Any> = emptyMap(), context: Any? = null): DataFetchingEnvironment {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment(buildExecutionContext())
            .source(source)
            .arguments(arguments)
            .context(context)
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
}
