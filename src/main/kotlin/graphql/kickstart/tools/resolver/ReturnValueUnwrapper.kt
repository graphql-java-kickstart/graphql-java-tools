package graphql.kickstart.tools.resolver

import graphql.execution.DataFetcherResult
import graphql.kickstart.tools.ResolverError
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper
import graphql.kickstart.tools.TypeClassMatcher.Unwrapped
import graphql.kickstart.tools.TypeClassMatcher.Unwrapping
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.future.asDeferred
import org.apache.commons.lang3.reflect.TypeUtils
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future
import java.util.function.Supplier

internal class ReturnValueUnwrapper private constructor(
    private val genericWrappers: List<GenericWrapper>,
    private val awaitable: Awaitable,
    private val isOutermostFuture: Boolean,
    private val nested: Step?
) {

    fun unwrap(value: Any, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? {
        val unwrapped = awaitable.requireOutermost(value.transformWithGenericWrapper(genericWrappers, environmentSupplier))
        return if (isOutermostFuture) unwrapped.then(nested, environmentSupplier) else unwrapped.thenUnlessNextAwaits(nested, environmentSupplier)
    }

    suspend fun unwrapSuspending(value: Any, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? {
        val unwrapped = unwrap(value, environmentSupplier)
        // the coroutine's future would complete with the future made below the outermost value, which graphql-java
        // doesn't wait on. asDeferred, so cancelling the coroutine doesn't cancel a future the resolver returned
        return if (nested != null && unwrapped is CompletionStage<*>) unwrapped.asDeferred().await() else unwrapped
    }

    companion object {
        fun create(
            method: Method,
            unwrapping: Unwrapping,
            valueClass: Class<*>?,
            isSuspendFunction: Boolean,
            isSubscription: Boolean,
            genericWrappers: List<GenericWrapper>
        ): ReturnValueUnwrapper {
            val unwrapped = unwrapping.unwrapped
            val outermost = unwrapped.firstOrNull() as? Unwrapped.Wrapper
            val belowOutermost = if (outermost == null) unwrapped else unwrapped.drop(1)
            val leafClass = TypeUtils.getRawType(unwrapping.javaType, null)
            // graphql-java completes a subscription's events one at a time
            val nested = if (isSubscription) null else steps(belowOutermost, Awaitable(method, leafClass, isSuspendFunction))
            return ReturnValueUnwrapper(genericWrappers, Awaitable(method, valueClass, isSuspendFunction), outermost?.isFuture == true, nested)
        }
    }
}

private sealed interface Step {
    fun unwrap(value: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any?
}

private class Await(private val wrapper: GenericWrapper, private val awaitable: Awaitable, private val next: Step?) : Step {
    override fun unwrap(value: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? =
        awaitable.requireNested(value?.let { wrapper.transformer(it, environmentSupplier.get()) }).then(next, environmentSupplier)
}

private class Peel(private val wrapper: GenericWrapper, private val awaitable: Awaitable, private val next: Step?) : Step {
    override fun unwrap(value: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? {
        val transformed = value?.let { wrapper.transformer(it, environmentSupplier.get()) }
        return if (next is Await) next.unwrap(transformed, environmentSupplier) else awaitable.requireNested(transformed).then(next, environmentSupplier)
    }
}

private class EachElement(private val element: Step) : Step {
    override fun unwrap(value: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? =
        if (value is Iterable<*>) value.map { element.unwrap(it, environmentSupplier) }.awaitAll() else value
}

private class DataOf(private val data: Step) : Step {
    @Suppress("UNCHECKED_CAST")
    override fun unwrap(value: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? =
        if (value is DataFetcherResult<*>) {
            data.unwrap(value.data, environmentSupplier).then { unwrapped -> (value as DataFetcherResult<Any?>).map { unwrapped } }
        } else {
            value
        }
}

private class ValueOf(private val present: Step) : Step {
    override fun unwrap(value: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? =
        if (value is Optional<*>) present.unwrap(value.orElse(null), environmentSupplier) else value
}

private val Unwrapped.Wrapper.isFuture
    get() = Future::class.java.isAssignableFrom(wrapper.type) || CompletionStage::class.java.isAssignableFrom(wrapper.type)

private fun steps(unwrapped: List<Unwrapped>, awaitable: Awaitable): Step? =
    unwrapped.foldRight(null as Step?) { it, next ->
        when (it) {
            is Unwrapped.Wrapper -> if (it.isFuture) Await(it.wrapper, awaitable, next) else Peel(it.wrapper, awaitable, next)
            Unwrapped.Data -> next?.let(::DataOf)
            Unwrapped.Present -> next?.let(::ValueOf)
            Unwrapped.Elements -> next?.let(::EachElement)
        }
    }

private fun Any?.thenUnlessNextAwaits(next: Step?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? =
    if (next is Await) next.unwrap(this, environmentSupplier) else then(next, environmentSupplier)

private fun Any?.then(next: Step?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? =
    if (next == null) this else then { next.unwrap(it, environmentSupplier) }

private inline fun Any?.then(crossinline next: (Any?) -> Any?): Any? =
    if (this is CompletionStage<*>) thenCompose { next(it).asCompletionStage() } else next(this)

private fun List<Any?>.awaitAll(): Any {
    if (none { it is CompletionStage<*> }) {
        return this
    }
    val futures = map { it.asCompletionStage().toCompletableFuture() }
    return CompletableFuture.allOf(*futures.toTypedArray()).thenApply { futures.map { it.join() } }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asCompletionStage(): CompletionStage<Any?> =
    this as? CompletionStage<Any?> ?: CompletableFuture.completedFuture(this)

private fun Any.transformWithGenericWrapper(
    genericWrappers: List<GenericWrapper>,
    environmentSupplier: Supplier<DataFetchingEnvironment>
): Any? {
    // the JIT doesn't optimize away the sequence below on this call path, so values that aren't wrappers, which is
    // most of them, return before it's allocated
    if (genericWrappers.none { it.type.isInstance(this) }) {
        return this
    }
    val genericWrapper = genericWrappers
        .asSequence()
        .filter { it.type.isInstance(this) }
        .sortedWith(CompareGenericWrappers)
        .firstOrNull()
        ?: return this

    // returned as is, even if null, so a transformer can map a value to null
    return genericWrapper.transformer(this, environmentSupplier.get())
}

// graphql-java only waits on a CompletionStage returned by a data fetcher, so any other future, or a future
// that a suspend function completes with, would reach it as the field value
private class Awaitable(private val method: Method, private val valueClass: Class<*>?, private val isSuspendFunction: Boolean) {

    fun requireOutermost(value: Any?): Any? {
        if (value !is Future<*> || valueClass?.isInstance(value) == true || value is CompletionStage<*> && !isSuspendFunction) {
            return value
        }

        throw ResolverError(
            if (isSuspendFunction) {
                "Suspend function '${method.resolverName}' resolved to a ${value.javaClass.name}, which graphql-java can't wait on. Return its value instead."
            } else {
                "Resolver method '${method.resolverName}' resolved to a ${value.javaClass.name}, which graphql-java can't wait on. " +
                    "Return a CompletionStage, such as a CompletableFuture, from the method or its generic wrapper transformer instead."
            }
        )
    }

    fun requireNested(value: Any?): Any? = if (value is CompletionStage<*>) value else requireOutermost(value)
}

private val Method.resolverName
    get() = "${declaringClass.name}.$name"

private class CompareGenericWrappers {
    companion object : Comparator<GenericWrapper> {
        override fun compare(w1: GenericWrapper, w2: GenericWrapper): Int = when {
            w1.type.isAssignableFrom(w2.type) -> 1
            else -> -1
        }
    }
}
