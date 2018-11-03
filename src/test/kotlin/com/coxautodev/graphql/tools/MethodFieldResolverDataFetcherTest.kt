package com.coxautodev.graphql.tools

import graphql.ExecutionResult
import graphql.execution.*
import graphql.execution.instrumentation.SimpleInstrumentation
import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.TypeName
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingEnvironmentBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert
import org.junit.Test
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
        return DataFetchingEnvironmentBuilder.newDataFetchingEnvironment()
                .source(source)
                .arguments(arguments)
                .context(context)
                .executionContext(buildExecutionContext())
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