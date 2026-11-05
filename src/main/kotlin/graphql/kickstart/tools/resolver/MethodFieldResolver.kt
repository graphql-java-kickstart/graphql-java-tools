package graphql.kickstart.tools.resolver

import graphql.GraphQLContext
import graphql.kickstart.tools.*
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.coroutineScope
import graphql.kickstart.tools.util.isSuspendFunction
import graphql.kickstart.tools.util.parameterCountWithoutContinuation
import graphql.kickstart.tools.util.typeArgument
import graphql.kickstart.tools.util.unwrap
import graphql.language.*
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.LightDataFetcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.future
import org.apache.commons.lang3.reflect.TypeUtils
import tools.jackson.databind.ObjectMapper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import java.util.function.Supplier
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.kotlinFunction

/**
 * @author Andrew Potter
 */
internal class MethodFieldResolver(
    field: FieldDefinition,
    search: FieldResolverScanner.Search,
    options: SchemaParserOptions,
    val method: Method,
    private val scalarNames: Set<String>
) : FieldResolver(field, search, options) {

    private val isSuspendFunction = method.isSuspendFunction()
    private val numberOfParameters = method.parameterCountWithoutContinuation()

    internal val objectMapper: ObjectMapper by lazy { options.objectMapperProvider.provide(field) }

    override fun createDataFetcher(): DataFetcher<*> {
        val args = mutableListOf<ArgumentPlaceholder>()
        val mapper = objectMapper

        if (this.search.requiredFirstParameterType != null) {
            val expectedType = this.search.requiredFirstParameterType

            args.add { environment ->
                val source = environment.getSource<Any>()
                    ?: throw ResolverError("Expected source object to not be null!")
                if (!expectedType.isAssignableFrom(source.javaClass)) {
                    throw ResolverError("Source type (${source.javaClass.name}) is not expected type (${expectedType.name})!")
                }

                source
            }
        }

        // Add an argument for each argument defined in the GraphQL schema
        this.field.inputValueDefinitions.forEachIndexed { index, definition ->

            val parameterType = this.getMethodParameterType(index)
                ?: throw ResolverError("Missing method type at position ${this.getJavaMethodParameterIndex(index)}, this is most likely a bug with graphql-java-tools")

            val isNonNull = definition.type is NonNullType
            val isOptional = TypeUtils.getRawType(parameterType, search.type) == Optional::class.java
            val argumentConverter = ArgumentConverter(mapper, parameterType)
            val parameterClass = parameterType.unwrap()
            val isConcreteScalar = isConcreteScalarType(definition.type, parameterType)

            args.add { environment ->
                val argumentPresent = environment.arguments.containsKey(definition.name)
                if (!argumentPresent && isNonNull) {
                    throw ResolverError("Missing required argument with name '${definition.name}', this is most likely a bug with graphql-java-tools")
                }

                val value = environment.arguments[definition.name]

                if (value == null && isOptional) {
                    if (options.inputArgumentOptionalDetectOmission && !environment.containsArgument(definition.name)) {
                        return@add null
                    }
                    return@add Optional.empty<Any>()
                }

                if (value == null || !isConcreteScalar || !parameterClass.isInstance(value)) {
                    return@add argumentConverter.convert(value)
                }

                return@add value
            }
        }

        // Add DataFetchingEnvironment/Context arguments, stopping before the Continuation parameter of a suspend function
        val firstTrailingParameter = field.inputValueDefinitions.size + search.firstArgumentIndex
        method.parameterTypes.slice(firstTrailingParameter until numberOfParameters).forEach { parameterType ->
            when (parameterType) {
                GraphQLContext::class.java -> args.add { environment -> environment.graphQlContext }

                options.contextClass -> args.add { environment ->
                    environment.graphQlContext.get<Any>(parameterType) ?: throw ResolverError(
                        "Resolver method '${method.declaringClass.name}.${method.name}' has a parameter of the context class " +
                            "'${parameterType.name}', but the GraphQLContext has no value for it. Put it in the GraphQLContext under " +
                            "the class key when building the ExecutionInput, for example " +
                            "graphQLContext(mapOf(${parameterType.simpleName}::class.java to context))."
                    )
                }

                else -> args.add { environment -> environment }
            }
        }

        val unwrapper = TypeShape.Unwrapper(returnShape, method, isSuspendFunction)

        return if (numberOfParameters > 0 || isSuspendFunction) {
            // requires arguments and environment or is a suspend function
            MethodFieldResolverDataFetcher(createSourceResolver(), this.method, args, options, isSuspendFunction, unwrapper)
        } else {
            // if there are no parameters an optimized version of the data fetcher can be used
            LightMethodFieldResolverDataFetcher(createSourceResolver(), this.method, unwrapper)
        }
    }

    /**
     * A concrete scalar type is a scalar type where values always coerce to the same Java type. The ID scalar type is not concrete
     * because values can be coerced to multiple different Java types (eg. String, Long, UUID). All values of a non-concrete scalar
     * type must be converted to the target method parameter type.
     */
    private fun isConcreteScalarType(type: Type<*>, genericParameterType: JavaType): Boolean {
        return when (type) {
            is ListType -> genericParameterType.typeArgument(List::class.java)
                ?.let { isConcreteScalarType(type.type, it) } ?: false

            is TypeName -> type.name in scalarNames && type.name != "ID"
            is NonNullType -> isConcreteScalarType(type.type, genericParameterType)
            else -> false
        }
    }

    // each shape is walked only when the scanner gets to it, so an earlier match's dictionary error is reported first
    override fun scanForMatches(): Sequence<TypeShape> = sequence {
        field.inputValueDefinitions.forEachIndexed { i, inputDefinition ->
            yield(TypeShape.parameterType(inputDefinition.type, getMethodParameterType(i)!!, search.type, options, ScanDictionary.MethodParameterReference(method, i)))
        }
        yield(returnShape)
    }

    private val returnShape by lazy {
        val declaredType = try {
            method.kotlinFunction?.returnType?.javaType ?: method.genericReturnType
        } catch (e: InternalError) {
            method.genericReturnType
        }
        // with type variables resolved, e.g. Future<Item> for get(): T in a subclass of Base<Future<Item>>
        val returnType = resolveTypeVariables(declaredType, search.type)
        TypeShape.returnType(field.type, returnType, search.isSubscription, search.type, options, ScanDictionary.ReturnValueReference(method))
    }

    private fun getJavaMethodParameterIndex(index: Int) = index + search.firstArgumentIndex

    internal fun getMethodParameterType(index: Int): JavaType? {
        val methodIndex = getJavaMethodParameterIndex(index)
        val parameters = method.parameterTypes

        return if (parameters.size > methodIndex) {
            resolveTypeVariables(method.genericParameterTypes[methodIndex], search.type)
        } else {
            null
        }
    }

    override fun toString() = "MethodFieldResolver{method=$method}"
}

internal class MethodFieldResolverDataFetcher(
    private val sourceResolver: SourceResolver,
    private val method: Method,
    private val args: List<ArgumentPlaceholder>,
    private val options: SchemaParserOptions,
    private val isSuspendFunction: Boolean,
    private val unwrapper: TypeShape.Unwrapper
) : DataFetcher<Any> {

    override fun get(environment: DataFetchingEnvironment): Any? {
        val source = sourceResolver.resolve(environment, null)
        val args = this.args.map { it(environment) }.toTypedArray()

        return if (isSuspendFunction) {
            // start undispatched so DataLoader loads are queued before graphql-java dispatches them,
            // which runs the block even if the context is already cancelled, hence ensureActive
            environment.coroutineScope().future(options.coroutineContextProvider.provide(), CoroutineStart.UNDISPATCHED) {
                ensureActive()
                invokeSuspend(source, method, args)?.let { unwrapper.unwrapSuspending(it) { environment } }
            }
        } else {
            invoke(method, source, args)?.let { unwrapper.unwrap(it) { environment } }
        }
    }

    /**
     * Function that returns the object used to fetch the data. It can be a DataFetcher or an entity.
     */
    @Suppress("unused")
    fun getWrappedFetchingObject(environment: DataFetchingEnvironment): Any {
        return sourceResolver.resolve(environment, null)
    }
}

/**
 * Similar to [MethodFieldResolverDataFetcher] but for light data fetchers which do not require the environment to be supplied unless generic wrappers are used.
 */
internal class LightMethodFieldResolverDataFetcher(
    private val sourceResolver: SourceResolver,
    private val method: Method,
    private val unwrapper: TypeShape.Unwrapper
) : LightDataFetcher<Any?> {

    override fun get(fieldDefinition: GraphQLFieldDefinition?, sourceObject: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? {
        val source = sourceResolver.resolve(null, sourceObject)

        return invoke(method, source, emptyArray())?.let { unwrapper.unwrap(it, environmentSupplier) }
    }

    override fun get(environment: DataFetchingEnvironment): Any? {
        return get(environment.fieldDefinition, sourceResolver.resolve(environment, null)) { environment }
    }

    /**
     * Function that returns the object used to fetch the data. It can be a DataFetcher or an entity.
     */
    @Suppress("unused")
    fun getWrappedFetchingObject(environment: DataFetchingEnvironment): Any {
        return sourceResolver.resolve(environment, null)
    }
}

private suspend inline fun invokeSuspend(target: Any, resolverMethod: Method, args: Array<Any?>): Any? {
    return suspendCoroutineUninterceptedOrReturn { continuation ->
        invoke(resolverMethod, target, args + continuation)
    }
}

private fun invoke(method: Method, instance: Any, args: Array<Any?>): Any? {
    try {
        return method.invoke(instance, *args)
    } catch (e: InvocationTargetException) {
        throw e.cause ?: RuntimeException("Unknown error occurred while invoking resolver method")
    }
}

internal typealias ArgumentPlaceholder = (DataFetchingEnvironment) -> Any?
