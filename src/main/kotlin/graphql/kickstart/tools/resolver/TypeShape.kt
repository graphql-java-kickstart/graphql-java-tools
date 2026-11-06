package graphql.kickstart.tools.resolver

import com.fasterxml.classmate.ResolvedType
import graphql.execution.DataFetcherResult
import graphql.kickstart.tools.ResolverError
import graphql.kickstart.tools.ScanDictionary
import graphql.kickstart.tools.SchemaClassScannerError
import graphql.kickstart.tools.SchemaParserOptions
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper
import graphql.kickstart.tools.util.GraphQLLangType
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.ParameterizedTypeImpl
import graphql.kickstart.tools.util.Primitives
import graphql.kickstart.tools.util.futureValueType
import graphql.kickstart.tools.util.isSubtypeOf
import graphql.kickstart.tools.util.typeArgument
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.TypeName
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.TypeUtil
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.future.asDeferred
import org.apache.commons.lang3.reflect.TypeUtils
import org.reactivestreams.Publisher
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future
import java.util.function.Supplier

// the leaf the dictionary binds and the layers a value is unwrapped through come from one walk, so they can't disagree
internal class TypeShape private constructor(
    private val graphQLType: GraphQLLangType,
    private val javaType: JavaType,
    val reference: ScanDictionary.Reference,
    val leafGraphQLType: GraphQLLangType,
    val leafJavaType: JavaType,
    private val layers: List<Layer>,
    private val declaredType: JavaType,
    private val unwrappedRootType: JavaType,
    private val isSubscription: Boolean,
    private val wrappers: GenericWrappers
) {

    fun error(msg: String) = matchError(graphQLType, reference, javaType, msg)

    class Unwrapper(shape: TypeShape, resolverKind: ResolverKind, resolverName: String, isSuspendFunction: Boolean = false) {
        private val returnsValueAsIs = !resolverKind.unwrapsObjectValues && shape.declaredType == Any::class.java
        private val wrappers = shape.wrappers
        private val awaitable: Awaitable
        private val isOutermostFuture: Boolean
        private val nested: Step?

        init {
            // the class the declared type unwraps to, so a value that happens to implement Future isn't mistaken for a wrapper.
            // Object (e.g. for a union) can't tell them apart, so then every future of a value declared as Future is checked
            val valueClass = (TypeUtils.getRawType(shape.unwrappedRootType, null) ?: Any::class.java)
                .takeUnless { it == Any::class.java && TypeUtils.getRawType(shape.declaredType, null) == Future::class.java }
            awaitable = Awaitable(resolverKind, resolverName, valueClass, isSuspendFunction)
            val outermost = shape.layers.firstOrNull() as? Layer.Wrapper
            val belowOutermost = if (outermost == null) shape.layers else shape.layers.drop(1)
            val leafClass = TypeUtils.getRawType(shape.leafJavaType, null)
            val awaits = Awaitable(resolverKind, resolverName, leafClass?.takeUnless { it == Any::class.java }, isSuspendFunction)
            val peels = Awaitable(resolverKind, resolverName, leafClass, isSuspendFunction)
            isOutermostFuture = outermost?.isFuture == true
            // graphql-java completes a subscription's events one at a time
            nested = if (shape.isSubscription) null else steps(belowOutermost, awaits, peels)
        }

        fun unwrap(value: Any, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? {
            if (returnsValueAsIs) {
                return value
            }
            val wrapper = wrappers.ofValue(value)
            val transformed = if (wrapper == null) value else wrapper.transformer(value, environmentSupplier.get())
            val unwrapped = awaitable.requireOutermost(transformed)
            return if (isOutermostFuture || wrapper?.transformerReturnsCompletionStage == true) {
                unwrapped.then(nested, environmentSupplier)
            } else {
                unwrapped.thenUnlessNextAwaits(nested, environmentSupplier)
            }
        }

        suspend fun unwrapSuspending(value: Any, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? {
            val unwrapped = unwrap(value, environmentSupplier)
            // the coroutine's future would complete with the future made below the outermost value, which graphql-java
            // doesn't wait on. asDeferred, so cancelling the coroutine doesn't cancel a future the resolver returned
            return if (nested != null && unwrapped is CompletionStage<*>) unwrapped.asDeferred().await() else unwrapped
        }
    }

    companion object {
        val containerTypes: List<Class<*>> get() = ContainerKind.entries.map { it.type }

        fun returnType(
            graphQLType: GraphQLLangType,
            declaredType: JavaType,
            isSubscription: Boolean,
            containingType: JavaType,
            options: SchemaParserOptions,
            reference: ScanDictionary.Reference
        ): TypeShape {
            val javaType = if (isSubscription) declaredType.asPublisher() else declaredType
            val walk = Walk(graphQLType, javaType, containingType, GenericWrappers(options.genericWrappers), reference, Position.RETURN_TYPE)
            val (leafGraphQLType, leafJavaType) = walk.leaf()
            return TypeShape(
                graphQLType, javaType, reference, leafGraphQLType, leafJavaType, walk.layers, resolveTypeVariables(declaredType, containingType),
                walk.unwrappedRootType, isSubscription, walk.wrappers
            )
        }

        fun parameterType(
            graphQLType: GraphQLLangType,
            javaType: JavaType,
            containingType: JavaType,
            options: SchemaParserOptions,
            reference: ScanDictionary.Reference
        ): TypeShape {
            val walk = Walk(graphQLType, javaType, containingType, GenericWrappers(options.genericWrappers), reference, Position.PARAMETER_TYPE)
            val (leafGraphQLType, leafJavaType) = walk.leaf()
            return TypeShape(graphQLType, javaType, reference, leafGraphQLType, leafJavaType, walk.layers, javaType, walk.unwrappedRootType, false, walk.wrappers)
        }
    }
}

// unlike the walk, keeps generic wrappers, wildcards and primitives, which argument conversion needs
internal fun resolveTypeVariables(type: JavaType, containingType: JavaType, resolving: Set<TypeVariable<*>> = emptySet()): JavaType {
    return when (type) {
        is ParameterizedType -> {
            val actualTypeArguments = type.actualTypeArguments.map { resolveTypeVariables(it, containingType, resolving) }.toTypedArray()
            ParameterizedTypeImpl(type.rawType as Class<*>, actualTypeArguments, type.ownerType?.let { resolveTypeVariables(it, containingType, resolving) })
        }
        is WildcardType -> TypeUtils.wildcardType()
            .withUpperBounds(*type.upperBounds.map { resolveTypeVariables(it, containingType, resolving) }.toTypedArray())
            .withLowerBounds(*type.lowerBounds.map { resolveTypeVariables(it, containingType, resolving) }.toTypedArray())
            .build()
        is ResolvedType -> {
            if (type.typeParameters.isEmpty()) {
                type.erasedType
            } else {
                val actualTypeArguments = type.typeParameters.map { resolveTypeVariables(it, containingType) }.toTypedArray()
                ParameterizedTypeImpl(type.erasedType, actualTypeArguments, null)
            }
        }
        is TypeVariable<*> -> {
            val genericDeclaration = type.genericDeclaration
            when {
                // only a variable leaked from a raw type can be bound to a type containing itself (e.g. T -> List<T>),
                // erase it like the raw type does instead of expanding it forever
                type in resolving -> TypeUtils.getRawType(type.bounds.first(), null) ?: Any::class.java
                genericDeclaration is Class<*> -> generateSequence(containingType) { (it as? ParameterizedType)?.ownerType }
                    // an inner class can also use the variables of its outer class, those are bound by its owner type (e.g. Connection<Owner>.Entry)
                    .firstNotNullOfOrNull { TypeUtils.getTypeArguments(it, genericDeclaration)?.get(type) }
                    // keep the full type argument (e.g. List<Foo>) rather than its raw class so nested generics aren't lost
                    ?.let { resolveTypeVariables(it, containingType, resolving + type) }
                    ?: type
                else -> type
            }
        }
        else -> type
    }
}

private enum class Position {
    RETURN_TYPE,
    PARAMETER_TYPE,
}

private class Walk(
    private val graphQLType: GraphQLLangType,
    private val javaType: JavaType,
    private val containingType: JavaType,
    val wrappers: GenericWrappers,
    private val reference: ScanDictionary.Reference,
    private val position: Position
) {
    val layers = mutableListOf<Layer>()

    lateinit var unwrappedRootType: JavaType
        private set

    fun leaf(): Pair<GraphQLLangType, JavaType> = walk(graphQLType, javaType, root = true)

    private fun walk(graphQLType: GraphQLLangType, javaType: JavaType, root: Boolean): Pair<GraphQLLangType, JavaType> {
        var realType = matchedType(javaType)
        if (root) {
            unwrappedRootType = realType
        }
        val contained = mutableSetOf<ContainerKind>()

        while (true) {
            val type = realType as? ParameterizedType ?: break
            val kind = ContainerKind.entries.find { type.isSubtypeOf(it.type) } ?: break
            kind.placementError(position, root)?.let { throw mismatch(it) }
            if (!contained.add(kind)) {
                throw mismatch("${kind.type.name} cannot be nested within itself")
            }
            layers += kind
            realType = matchedType(type.actualTypeArguments.first())
        }

        return when (graphQLType) {
            is NonNullType -> {
                contained.find { !it.canBeNonNull }?.let { throw mismatch("graphql type is marked as nonnull but ${it.type.name} was used") }
                walk(graphQLType.type, realType, root = false)
            }

            is ListType -> when {
                realType is ParameterizedType && realType.isSubtypeOf(Iterable::class.java) -> {
                    layers += Layer.Elements
                    walk(graphQLType.type, realType.actualTypeArguments.first(), root = false)
                }
                realType is Class<*> && realType.isArray ->
                    walk(graphQLType.type, realType.componentType, root = false)
                // e.g. a property map value: the element type is unknown, so match it as Object too
                realType == Any::class.java ->
                    walk(graphQLType.type, realType, root = false)
                else ->
                    throw mismatch("Java class is not a List or generic type information was lost: $realType")
            }

            else -> graphQLType to realType
        }
    }

    private fun matchedType(javaType: JavaType): JavaType {
        return when (val type = resolveTypeVariables(javaType, containingType)) {
            is ParameterizedType -> {
                val wrapper = wrappers.ofDeclaredType(type) ?: return type

                val typeArguments = type.actualTypeArguments
                if (typeArguments.size <= wrapper.index) {
                    throw IndexOutOfBoundsException("Generic type '${TypeUtils.toString(type)}' does not have a type argument at index ${wrapper.index}!")
                }

                layers += Layer.Wrapper(wrapper)
                matchedType(wrapper.schemaWrapper.invoke(typeArguments[wrapper.index]))
            }
            is TypeVariable<*> -> error("Could not resolve type variable '${TypeUtils.toLongString(type)}' relative to ${TypeUtils.toString(containingType)}")
            // Kotlin reflection gives a suspend function returning List<Foo> the type List<? extends Foo> when Foo is open.
            // A type variable bound stays as it is, since a method's own variable can't be resolved and a scalar doesn't need it
            is WildcardType -> when (val bound = type.upperBounds.firstOrNull() ?: error("Unable to unwrap type, wildcard has no upper bound: $type")) {
                is ParameterizedType -> matchedType(bound)
                else -> bound
            }
            is Class<*> -> if (type.isPrimitive) Primitives.wrap(type) else type
            else -> error("Unable to unwrap type: $type")
        }
    }

    private fun mismatch(msg: String) = matchError(graphQLType, reference, javaType, msg)
}

private fun matchError(graphQLType: GraphQLLangType, reference: ScanDictionary.Reference, javaType: JavaType, msg: String) =
    SchemaClassScannerError("Unable to match type definition (${TypeUtil.simplePrint(graphQLType)}) for reference $reference with java type ($javaType): $msg")

// generic wrappers only match their exact type, so a Publisher implementation (e.g. Flux), or a future of one,
// is matched as a plain Publisher of its events
private fun JavaType.asPublisher(): JavaType =
    (futureValueType() ?: this).typeArgument(Publisher::class.java)
        ?.let { TypeUtils.parameterize(Publisher::class.java, it) }
        ?: this

// picks the subscription method before its return type is walked, so it reads the erased type, where suspend functions and
// unbounded type variables are Object and the actual return type is unknown
internal fun returnsPublisher(erasedType: Class<*>, genericType: JavaType, wrappers: List<GenericWrapper>): Boolean =
    erasedType == Any::class.java
        || Publisher::class.java.isAssignableFrom(erasedType)
        || returnsPublisherFuture(genericType)
        || ReceiveChannel::class.java.isAssignableFrom(erasedType) && wrappers.any { it.type == erasedType && it.canTransformToPublisher() }

private fun returnsPublisherFuture(genericType: JavaType) =
    genericType.futureValueType()
        ?.let { if (it is WildcardType) it.upperBounds.first() else it }
        ?.let { TypeUtils.isAssignable(it, Publisher::class.java) } == true

// lambdas are erased to return Object, so this only checks that the transformer can return a Publisher
private fun GenericWrapper.canTransformToPublisher() =
    transformer.javaClass.declaredMethods
        .filter { it.name == "invoke" }
        .any { it.returnType.isAssignableFrom(Publisher::class.java) }

internal fun isOptionalParameter(parameterType: JavaType, containingType: JavaType): Boolean =
    TypeUtils.getRawType(parameterType, containingType) == Optional::class.java

internal fun isConcreteScalarType(type: GraphQLLangType, parameterType: JavaType, scalarNames: Set<String>): Boolean =
    when (type) {
        is ListType -> parameterType.typeArgument(List::class.java)
            ?.let { isConcreteScalarType(type.type, it, scalarNames) } ?: false

        is TypeName -> type.name in scalarNames && type.name != "ID"
        is NonNullType -> isConcreteScalarType(type.type, parameterType, scalarNames)
        else -> false
    }

// The two rules differ on purpose: making the build pick by instance would unwrap a Flux as a Publisher, and making the fetch
// pick by declared type would stop a CompletableFuture returned for a declared Future from getting its own wrapper
private class GenericWrappers(private val wrappers: List<GenericWrapper>) {

    fun ofDeclaredType(type: ParameterizedType): GenericWrapper? = wrappers.find { it.type == type.rawType }

    fun ofValue(value: Any): GenericWrapper? {
        // the JIT doesn't optimize away the sequence below on this call path, so values that aren't wrappers, which is
        // most of them, return before it's allocated
        if (wrappers.none { it.type.isInstance(value) }) {
            return null
        }
        return wrappers.asSequence()
            .filter { it.type.isInstance(value) }
            .sortedWith(CompareGenericWrappers)
            .firstOrNull()
    }
}

private class CompareGenericWrappers {
    companion object : Comparator<GenericWrapper> {
        override fun compare(w1: GenericWrapper, w2: GenericWrapper): Int = when {
            w1.type.isAssignableFrom(w2.type) -> 1
            else -> -1
        }
    }
}

private sealed interface Layer {
    class Wrapper(val wrapper: GenericWrapper) : Layer {
        val isFuture get() = Future::class.java.isAssignableFrom(wrapper.type) || CompletionStage::class.java.isAssignableFrom(wrapper.type)
    }

    object Elements : Layer
}

private enum class ContainerKind(val type: Class<*>, val canBeNonNull: Boolean) : Layer {
    DATA_FETCHER_RESULT(DataFetcherResult::class.java, canBeNonNull = true) {
        override fun placementError(position: Position, root: Boolean) =
            if (position != Position.RETURN_TYPE) "${type.name} can only be used as a return type" else null

        override fun step(next: Step?, inner: Layer?): Step? = next?.let(::DataOf)
    },
    OPTIONAL(Optional::class.java, canBeNonNull = false) {
        override fun placementError(position: Position, root: Boolean) =
            if (position == Position.RETURN_TYPE && !root) "${type.name} can only be used at the top level of a return type" else null

        // graphql-java reads a DataFetcherResult and then an Optional in its data, so an Optional around one is ours to unwrap
        override fun step(next: Step?, inner: Layer?): Step? = if (next != null || inner == DATA_FETCHER_RESULT) ValueOf(next) else null
    };

    abstract fun placementError(position: Position, root: Boolean): String?

    // null leaves the value to graphql-java
    abstract fun step(next: Step?, inner: Layer?): Step?
}

private fun steps(layers: List<Layer>, awaits: Awaitable, peels: Awaitable): Step? {
    val outermost = layers.firstOrNull() ?: return null
    val inner = layers.subList(1, layers.size)
    val next = steps(inner, awaits, peels)
    return when (outermost) {
        is Layer.Wrapper -> if (outermost.isFuture) Await(outermost.wrapper, awaits, next) else Peel(outermost.wrapper, peels, next)
        is ContainerKind -> outermost.step(next, inner.firstOrNull())
        Layer.Elements -> next?.let(::EachElement)
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
        return if (next is Await && !wrapper.transformerReturnsCompletionStage) {
            next.unwrap(transformed, environmentSupplier)
        } else {
            awaitable.requireNested(transformed).then(next, environmentSupplier)
        }
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

private class ValueOf(private val present: Step?) : Step {
    override fun unwrap(value: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? = when {
        value !is Optional<*> -> value
        present == null -> value.orElse(null)
        else -> present.unwrap(value.orElse(null), environmentSupplier)
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

// graphql-java only waits on a CompletionStage returned by a data fetcher, so any other future, or a future
// that a suspend function completes with, would reach it as the field value
private class Awaitable(
    private val resolverKind: ResolverKind,
    private val resolverName: String,
    private val valueClass: Class<*>?,
    private val isSuspendFunction: Boolean
) {

    fun requireOutermost(value: Any?): Any? =
        if (value !is Future<*> || valueClass?.isInstance(value) == true || value is CompletionStage<*> && !isSuspendFunction) value else fail(value)

    fun requireNested(value: Any?): Any? =
        if (value !is Future<*> || value is CompletionStage<*> || valueClass?.isInstance(value) == true) value else fail(value)

    private fun fail(value: Any): Nothing = throw ResolverError(
        if (isSuspendFunction) {
            "Suspend function '$resolverName' resolved to a ${value.javaClass.name}, which graphql-java can't wait on. Return its value instead."
        } else {
            "Resolver ${resolverKind.noun} '$resolverName' resolved to a ${value.javaClass.name}, which graphql-java can't wait on. " +
                resolverKind.holdCompletionStage
        }
    )
}
