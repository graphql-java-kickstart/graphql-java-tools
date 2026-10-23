package graphql.kickstart.tools.resolver

import graphql.execution.DataFetcherResult
import graphql.kickstart.tools.ResolverError
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper
import graphql.kickstart.tools.TypeClassMatcher.Unwrapped
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.future.asDeferred
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future
import java.util.function.Supplier

internal class ReturnValueUnwrapper private constructor(
    private val genericWrappers: List<GenericWrapper>,
    private val method: Method,
    private val valueClass: Class<*>?,
    private val isOutermostFuture: Boolean,
    private val nested: Step?
) {

    fun unwrap(value: Any, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? =
        unwrap(value, environmentSupplier, isSuspendFunction = false)

    suspend fun unwrapSuspending(value: Any, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? {
        val unwrapped = unwrap(value, environmentSupplier, isSuspendFunction = true)
        // the coroutine's future would complete with the future made below the outermost value, which graphql-java
        // doesn't wait on. asDeferred, so cancelling the coroutine doesn't cancel a future the resolver returned
        return if (nested != null && unwrapped is CompletionStage<*>) unwrapped.asDeferred().await() else unwrapped
    }

    private fun unwrap(value: Any, environmentSupplier: Supplier<DataFetchingEnvironment>, isSuspendFunction: Boolean): Any? {
        val unwrapped = value.transformWithGenericWrapper(genericWrappers, environmentSupplier).requireAwaitable(valueClass, method, isSuspendFunction)
        return if (isOutermostFuture) unwrapped.then(nested, environmentSupplier) else unwrapped.thenUnlessNextAwaits(nested, environmentSupplier)
    }

    companion object {
        fun create(method: Method, unwrapped: List<Unwrapped>, valueClass: Class<*>?, isSubscription: Boolean, genericWrappers: List<GenericWrapper>): ReturnValueUnwrapper {
            val outermost = unwrapped.firstOrNull() as? Unwrapped.Wrapper
            // graphql-java completes a subscription's events one at a time
            val nested = if (isSubscription) null else steps(if (outermost == null) unwrapped else unwrapped.drop(1), method)
            return ReturnValueUnwrapper(genericWrappers, method, valueClass, outermost?.isFuture == true, nested)
        }
    }
}

private sealed interface Step {
    fun unwrap(value: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any?
}

private class Await(private val wrapper: GenericWrapper, private val method: Method, private val next: Step?) : Step {
    override fun unwrap(value: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? =
        value?.let { wrapper.transformer(it, environmentSupplier.get()) }.requireCompletionStage(method).then(next, environmentSupplier)
}

private class Peel(private val wrapper: GenericWrapper, private val next: Step?) : Step {
    override fun unwrap(value: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? =
        value?.let { wrapper.transformer(it, environmentSupplier.get()) }.thenUnlessNextAwaits(next, environmentSupplier)
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

private fun steps(unwrapped: List<Unwrapped>, method: Method): Step? =
    unwrapped.foldRight(null as Step?) { it, next ->
        when (it) {
            is Unwrapped.Wrapper -> if (it.isFuture) Await(it.wrapper, method, next) else Peel(it.wrapper, next)
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
private fun Any?.requireAwaitable(valueClass: Class<*>?, method: Method, isSuspendFunction: Boolean): Any? {
    if (this !is Future<*> || valueClass?.isInstance(this) == true) {
        return this
    }

    if (isSuspendFunction) {
        throw ResolverError("Suspend function '${method.resolverName}' resolved to a ${javaClass.name}, which graphql-java can't wait on. Return its value instead.")
    }

    return requireCompletionStage(method)
}

private fun Any?.requireCompletionStage(method: Method): Any? {
    if (this is Future<*> && this !is CompletionStage<*>) {
        throw ResolverError(
            "Resolver method '${method.resolverName}' resolved to a ${javaClass.name}, which graphql-java can't wait on. " +
                "Return a CompletionStage, such as a CompletableFuture, from the method or its generic wrapper transformer instead."
        )
    }

    return this
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
