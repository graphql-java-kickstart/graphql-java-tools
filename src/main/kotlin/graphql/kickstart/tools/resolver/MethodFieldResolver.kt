package graphql.kickstart.tools.resolver

import com.fasterxml.jackson.core.type.TypeReference
import graphql.GraphQLContext
import graphql.kickstart.tools.*
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.coroutineScope
import graphql.kickstart.tools.util.unwrap
import graphql.language.*
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLTypeUtil.isScalar
import graphql.schema.LightDataFetcher
import kotlinx.coroutines.future.future
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import java.util.function.Supplier
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.kotlinFunction

/**
 * @author Andrew Potter
 */
internal class MethodFieldResolver(
    field: FieldDefinition,
    search: FieldResolverScanner.Search,
    options: SchemaParserOptions,
    val method: Method
) : FieldResolver(field, search, options, search.type) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val isSuspendFunction = method.isSuspendFunction()
    private val numberOfParameters = method.kotlinFunction?.valueParameters?.size ?: method.parameterCount
    private val hasAdditionalParameter = numberOfParameters == (field.inputValueDefinitions.size + getIndexOffset() + 1)

    override fun createDataFetcher(): DataFetcher<*> {
        val args = mutableListOf<ArgumentPlaceholder>()
        val mapper = options.objectMapperProvider.provide(field)

        // Add source argument if this is a resolver (but not a root resolver)
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
                ?.apply { genericType.getRawClass(this) }
                ?: throw ResolverError("Missing method type at position ${this.getJavaMethodParameterIndex(index)}, this is most likely a bug with graphql-java-tools")

            val isNonNull = definition.type is NonNullType
            val isOptional = this.genericType.getRawClass(parameterType) == Optional::class.java

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

                if (value == null || shouldValueBeConverted(value, definition, parameterType, environment)) {
                    return@add mapper.convertValue(value, object : TypeReference<Any>() {
                        override fun getType() = parameterType
                    })
                }

                return@add value
            }
        }

        // Add DataFetchingEnvironment/Context argument
        if (this.hasAdditionalParameter) {
            when (this.method.parameterTypes.last()) {
                null -> throw ResolverError("Expected at least one argument but got none, this is most likely a bug with graphql-java-tools")
                options.contextClass -> args.add { environment ->
                    val context: Any? = environment.graphQlContext[options.contextClass]
                    if (context != null) {
                        context
                    } else {
                        log.warn(
                            "Generic context class has been deprecated by graphql-java. " +
                                "To continue using a custom context class as the last parameter in resolver methods " +
                                "please insert it into the GraphQLContext map when building the ExecutionInput. " +
                                "This warning will become an error in the future."
                        )
                        environment.getContext() // TODO: remove deprecated use in next major release
                    }
                }

                GraphQLContext::class.java -> args.add { environment -> environment.graphQlContext }
                else -> args.add { environment -> environment }
            }
        }

        return if (numberOfParameters > 0 || isSuspendFunction) {
            // requires arguments and environment or is a suspend function
            MethodFieldResolverDataFetcher(createSourceResolver(), this.method, args, options, isSuspendFunction)
        } else {
            // if there are no parameters an optimized version of the data fetcher can be used
            LightMethodFieldResolverDataFetcher(createSourceResolver(), this.method, options)
        }
    }

    private fun shouldValueBeConverted(value: Any, definition: InputValueDefinition, parameterType: JavaType, environment: DataFetchingEnvironment): Boolean {
        return !parameterType.unwrap().isAssignableFrom(value.javaClass) || !isConcreteScalarType(environment, definition.type, parameterType)
    }

    /**
     * A concrete scalar type is a scalar type where values always coerce to the same Java type. The ID scalar type is not concrete
     * because values can be coerced to multiple different Java types (eg. String, Long, UUID). All values of a non-concrete scalar
     * type must be converted to the target method parameter type.
     */
    private fun isConcreteScalarType(environment: DataFetchingEnvironment, type: Type<*>, genericParameterType: JavaType): Boolean {
        return when (type) {
            is ListType -> List::class.java.isAssignableFrom(this.genericType.getRawClass(genericParameterType))
                && isConcreteScalarType(environment, type.type, this.genericType.unwrapGenericType(genericParameterType))

            is TypeName -> environment.graphQLSchema?.getType(type.name)?.let { isScalar(it) && type.name != "ID" }
                ?: false

            is NonNullType -> isConcreteScalarType(environment, type.type, genericParameterType)
            else -> false
        }
    }

    override fun scanForMatches(): List<TypeClassMatcher.PotentialMatch> {
        val unwrappedGenericType = genericType.unwrapGenericType(
            try {
                method.kotlinFunction?.returnType?.javaType ?: method.genericReturnType
            } catch (e: InternalError) {
                method.genericReturnType
            }
        )
        val returnValueMatch = TypeClassMatcher.PotentialMatch.returnValue(field.type, unwrappedGenericType, genericType, SchemaClassScanner.ReturnValueReference(method))

        return field.inputValueDefinitions.mapIndexed { i, inputDefinition ->
            TypeClassMatcher.PotentialMatch.parameterType(inputDefinition.type, getMethodParameterType(i)!!, genericType, SchemaClassScanner.MethodParameterReference(method, i))
        } + listOf(returnValueMatch)
    }

    private fun getIndexOffset(): Int {
        return if (resolverInfo is DataClassTypeResolverInfo && !method.declaringClass.isAssignableFrom(resolverInfo.dataClassType)) {
            1
        } else {
            0
        }
    }

    private fun getJavaMethodParameterIndex(index: Int) = index + getIndexOffset()

    private fun getMethodParameterType(index: Int): JavaType? {
        val methodIndex = getJavaMethodParameterIndex(index)
        val parameters = method.parameterTypes

        return if (parameters.size > methodIndex) {
            method.genericParameterTypes[methodIndex]
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
    private val isSuspendFunction: Boolean
) : DataFetcher<Any> {

    override fun get(environment: DataFetchingEnvironment): Any? {
        val source = sourceResolver.resolve(environment, null)
        val args = this.args.map { it(environment) }.toTypedArray()

        return if (isSuspendFunction) {
            environment.coroutineScope().future(options.coroutineContextProvider.provide()) {
                invokeSuspend(source, method, args)?.transformWithGenericWrapper(options.genericWrappers) { environment }
            }
        } else {
            invoke(method, source, args)?.transformWithGenericWrapper(options.genericWrappers) { environment }
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
    private val options: SchemaParserOptions
) : LightDataFetcher<Any?> {

    override fun get(fieldDefinition: GraphQLFieldDefinition, sourceObject: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? {
        val source = sourceResolver.resolve(null, sourceObject)

        return invoke(method, source, emptyArray())?.transformWithGenericWrapper(options.genericWrappers, environmentSupplier)
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

private fun Any.transformWithGenericWrapper(
    genericWrappers: List<GenericWrapper>,
    environmentSupplier: Supplier<DataFetchingEnvironment>
): Any {
    return genericWrappers
        .asSequence()
        .filter { it.type.isInstance(this) }
        .sortedWith(CompareGenericWrappers)
        .firstOrNull()
        ?.transformer?.invoke(this, environmentSupplier.get()) ?: this
}

private class CompareGenericWrappers {
    companion object : Comparator<GenericWrapper> {
        override fun compare(w1: GenericWrapper, w2: GenericWrapper): Int = when {
            w1.type.isAssignableFrom(w2.type) -> 1
            else -> -1
        }
    }
}

private fun Method.isSuspendFunction(): Boolean {
    return try {
        this.kotlinFunction?.isSuspend == true
    } catch (e: InternalError) {
        false
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
