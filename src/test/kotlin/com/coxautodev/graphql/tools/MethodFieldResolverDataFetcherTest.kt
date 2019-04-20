package com.coxautodev.graphql.tools

import graphql.ExecutionResult
import graphql.execution.*
import graphql.execution.instrumentation.SimpleInstrumentation
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
    fun `data fetcher executes suspend function on coroutineContext defined by options`() {
        // setup
        val dispatcher = Dispatchers.IO
        val job = Job()
        val options = SchemaParserOptions.Builder()
                .coroutineContext(dispatcher + job)
                .build()

        val resolver = createFetcher("active", object : GraphQLResolver<DataClass> {
            suspend fun isActive(data: DataClass): Boolean {
                return coroutineContext[dispatcher.key] == dispatcher &&
                        coroutineContext[Job] == job.children.first()
            }
        }, options = options)

        // expect
        @Suppress("UNCHECKED_CAST")
        val future = resolver.get(createEnvironment(DataClass())) as CompletableFuture<Boolean>
        Assert.assertTrue(future.get())
    }

    @ExperimentalCoroutinesApi
    @Test
    fun `canceling subscription Publisher also cancels underlying Kotlin coroutine channel`() {
        // setup
        val channel = Channel<String>(10)
        channel.offer("A")
        channel.offer("B")

        val resolver = createFetcher("onDataNameChanged", object : GraphQLResolver<DataClass> {
            fun onDataNameChanged(date: DataClass): ReceiveChannel<String> {
                return channel
            }
        })

        // expect
        @Suppress("UNCHECKED_CAST")
        val publisher = resolver.get(createEnvironment(DataClass())) as Publisher<String>
        val subscriber = TestEnvironment().newManualSubscriber(publisher)

        Assert.assertEquals("A", subscriber.requestNextElement())

        subscriber.cancel()
        Thread.sleep(100)
        Assert.assertTrue(channel.isClosedForReceive)
    }

    @Test
    fun `canceling underlying Kotlin coroutine channel also cancels subscription Publisher`() {
        // setup
        val channel = Channel<String>(10)
        channel.offer("A")
        channel.close(IllegalStateException("Channel error"))

        val resolver = createFetcher("onDataNameChanged", object : GraphQLResolver<DataClass> {
            fun onDataNameChanged(date: DataClass): ReceiveChannel<String> {
                return channel
            }
        })

        // expect
        @Suppress("UNCHECKED_CAST")
        val publisher = resolver.get(createEnvironment(DataClass())) as Publisher<String>
        val subscriber = TestEnvironment().newManualSubscriber(publisher)

        Assert.assertEquals("A", subscriber.requestNextElement())
        subscriber.expectErrorWithMessage(IllegalStateException::class.java, "Channel error")
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
