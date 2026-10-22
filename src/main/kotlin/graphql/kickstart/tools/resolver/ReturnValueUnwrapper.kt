package graphql.kickstart.tools.resolver

import graphql.execution.DataFetcherResult
import graphql.kickstart.tools.GenericTypeResolver
import graphql.kickstart.tools.ResolverError
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper
import graphql.kickstart.tools.util.GraphQLLangType
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.isSubtypeOf
import graphql.kickstart.tools.util.unwrap
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.ScalarInfo
import kotlinx.coroutines.future.asDeferred
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future
import java.util.function.Supplier

/**
 * Turns what a resolver method returned into the value graphql-java completes its field with.
 *
 * The schema build matches the field's GraphQL type against the return type with generic wrappers unwrapped at every
 * level, e.g. `List<Try<Item>>` against `[Item]`. graphql-java only waits on a CompletionStage that the data fetcher
 * returns and doesn't know generic wrappers, so the value is unwrapped at the same positions, and futures below the
 * outermost value are combined into one: one failed element fails the whole field.
 */
internal class ReturnValueUnwrapper private constructor(
    private val genericWrappers: List<GenericWrapper>,
    private val method: Method,
    private val valueClass: Class<*>?,
    /** null for most fields: nothing is unwrapped below the outermost value */
    private val nested: Step?
) {

    fun unwrap(value: Any, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? =
        value.transformWithGenericWrapper(genericWrappers, environmentSupplier)
            .requireAwaitable(valueClass, method, isSuspendFunction = false)
            .then(nested, environmentSupplier)

    suspend fun unwrapSuspending(value: Any, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? {
        val unwrapped = value.transformWithGenericWrapper(genericWrappers, environmentSupplier)
            .requireAwaitable(valueClass, method, isSuspendFunction = true)
            .then(nested, environmentSupplier)
        // the coroutine's future would complete with the future made below the outermost value, which graphql-java
        // doesn't wait on. asDeferred, so cancelling the coroutine doesn't cancel a future the resolver returned
        return if (nested != null && unwrapped is CompletionStage<*>) unwrapped.asDeferred().await() else unwrapped
    }

    companion object {
        /**
         * @param returnType the declared return type with type variables resolved, generic wrappers kept
         * @param valueClass the class the outermost value unwraps to, see [requireAwaitable]
         */
        fun create(
            method: Method,
            returnType: JavaType,
            valueClass: Class<*>?,
            fieldType: GraphQLLangType,
            isSubscription: Boolean,
            typeResolver: GenericTypeResolver,
            genericWrappers: List<GenericWrapper>,
            scalarNames: Set<String>
        ): ReturnValueUnwrapper {
            val nested = when {
                // graphql-java completes a subscription's events one at a time
                isSubscription -> null
                !hasGenericWrapperBelow(returnType, genericWrappers) -> null
                // a custom scalar's Coercing may take the wrapper itself, e.g. a JSON scalar
                fieldType.unwrap().name.let { it in scalarNames && it !in ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS } -> null
                else -> nestedSteps(fieldType, returnType, typeResolver, genericWrappers)
            }
            return ReturnValueUnwrapper(genericWrappers, method, valueClass, nested)
        }
    }
}

/** A position below the outermost value where the schema build unwrapped something. */
private sealed interface Step {
    /** The value unwrapped, or a CompletionStage of it. A value of another shape than expected, null included, is kept. */
    fun unwrap(value: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any?
}

/** A generic wrapper, e.g. each `Try` of `List<Try<Item>>`. Every chain of steps ends in one. */
private class Peel(private val genericWrappers: List<GenericWrapper>, private val next: Step?) : Step {
    override fun unwrap(value: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? =
        value?.transformWithGenericWrapper(genericWrappers, environmentSupplier).then(next, environmentSupplier)
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

/** What the schema build unwraps in a return type. */
private sealed interface Unwrapped {
    class Wrapper(rawType: Class<*>) : Unwrapped {
        val isFuture = Future::class.java.isAssignableFrom(rawType) || CompletionStage::class.java.isAssignableFrom(rawType)
    }

    object Data : Unwrapped
    object Present : Unwrapped
    object Elements : Unwrapped
}

private fun nestedSteps(fieldType: GraphQLLangType, returnType: JavaType, typeResolver: GenericTypeResolver, genericWrappers: List<GenericWrapper>): Step? {
    val unwrapped = mutableListOf<Unwrapped>()
    val onUnwrap = { wrapped: ParameterizedType -> unwrapped += Unwrapped.Wrapper(wrapped.rawType as Class<*>) }

    // TypeClassMatcher.match without its errors, which the build has thrown already: generic wrappers, then a
    // DataFetcherResult, then an Optional, then the GraphQL type: NonNull matches the same Java type again, a list goes
    // on with the Iterable's elements, anything else ends the walk
    fun addLevel(graphQLType: GraphQLLangType, javaType: JavaType) {
        var type = typeResolver.resolve(javaType, onUnwrap)
        if (type is ParameterizedType && type.isSubtypeOf(DataFetcherResult::class.java)) {
            unwrapped += Unwrapped.Data
            type = typeResolver.resolve(type.actualTypeArguments.first(), onUnwrap)
        }
        if (type is ParameterizedType && type.isSubtypeOf(Optional::class.java)) {
            unwrapped += Unwrapped.Present
            type = typeResolver.resolve(type.actualTypeArguments.first(), onUnwrap)
        }
        when (graphQLType) {
            is NonNullType -> addLevel(graphQLType.type, type)
            is ListType -> if (type is ParameterizedType && type.isSubtypeOf(Iterable::class.java)) {
                unwrapped += Unwrapped.Elements
                addLevel(graphQLType.type, type.actualTypeArguments.first())
            }
            else -> Unit
        }
    }

    // MethodFieldResolver.scanForMatches matches the resolved return type
    addLevel(fieldType, typeResolver.resolve(returnType, onUnwrap))
    val awaited = unwrapped.filterIndexed { i, it ->
        // a future returned by the wrapper before it is waited on by whatever takes that wrapper's result,
        // e.g. graphql-java for `Try<CompletableFuture<Item>>`
        !(it is Unwrapped.Wrapper && it.isFuture && (unwrapped.getOrNull(i - 1) as? Unwrapped.Wrapper)?.isFuture == false)
    }
    // the outermost value is unwrapped by the data fetcher itself
    val belowOutermost = if (awaited.firstOrNull() is Unwrapped.Wrapper) awaited.drop(1) else awaited
    // only a generic wrapper needs a step of its own, the others only lead to one
    return belowOutermost.foldRight(null as Step?) { it, next ->
        when (it) {
            is Unwrapped.Wrapper -> Peel(genericWrappers, next)
            Unwrapped.Data -> next?.let(::DataOf)
            Unwrapped.Present -> next?.let(::ValueOf)
            Unwrapped.Elements -> next?.let(::EachElement)
        }
    }
}

// without one, nothing below the outermost value can be unwrapped (unless a schemaWrapper adds one), so most fields
// skip the walk
private fun hasGenericWrapperBelow(type: JavaType, genericWrappers: List<GenericWrapper>): Boolean =
    type is ParameterizedType && type.actualTypeArguments.any { it.mentionsGenericWrapper(genericWrappers) }

private fun JavaType.mentionsGenericWrapper(genericWrappers: List<GenericWrapper>): Boolean = when (this) {
    is ParameterizedType -> genericWrappers.any { it.type == rawType } || actualTypeArguments.any { it.mentionsGenericWrapper(genericWrappers) }
    is WildcardType -> upperBounds.any { it.mentionsGenericWrapper(genericWrappers) }
    else -> false
}

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

    val resolverName = "${method.declaringClass.name}.${method.name}"
    if (isSuspendFunction) {
        throw ResolverError("Suspend function '$resolverName' resolved to a ${javaClass.name}, which graphql-java can't wait on. Return its value instead.")
    }
    if (this !is CompletionStage<*>) {
        throw ResolverError(
            "Resolver method '$resolverName' resolved to a ${javaClass.name}, which graphql-java can't wait on. " +
                "Return a CompletionStage, such as a CompletableFuture, from the method or its generic wrapper transformer instead."
        )
    }

    return this
}

private class CompareGenericWrappers {
    companion object : Comparator<GenericWrapper> {
        override fun compare(w1: GenericWrapper, w2: GenericWrapper): Int = when {
            w1.type.isAssignableFrom(w2.type) -> 1
            else -> -1
        }
    }
}
