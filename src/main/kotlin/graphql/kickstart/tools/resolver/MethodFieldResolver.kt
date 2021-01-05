package graphql.kickstart.tools.resolver

import com.fasterxml.jackson.annotation.JsonIgnore
import graphql.TrivialDataFetcher
import graphql.kickstart.tools.*
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.coroutineScope
import graphql.kickstart.tools.util.isTrivialDataFetcher
import graphql.kickstart.tools.util.unwrap
import graphql.language.*
import graphql.schema.*
import graphql.schema.GraphQLTypeUtil.*
import kotlinx.coroutines.future.future
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType
import java.util.*
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.reflect.KClass
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

    private val additionalLastArgument =
        try {
            method.kotlinFunction?.valueParameters?.size ?: method.parameterCount == (field.inputValueDefinitions.size + getIndexOffset() + 1)
        } catch (e: InternalError) {
            method.parameterCount == (field.inputValueDefinitions.size + getIndexOffset() + 1)
        }

    override fun createDataFetcher(): DataFetcher<*> {
        val args = mutableListOf<ArgumentPlaceholder>()

        // Add source argument if this is a resolver (but not a root resolver)
        if (this.search.requiredFirstParameterType != null) {
            val expectedType = this.search.requiredFirstParameterType

            args.add { environment ->
                val source = environment.getSource<Any>()
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


                return@add parseInput(value, definition.type, parameterType, environment)
            }
        }

        // Add DataFetchingEnvironment/Context argument
        if (this.additionalLastArgument) {
            when (this.method.parameterTypes.last()) {
                null -> throw ResolverError("Expected at least one argument but got none, this is most likely a bug with graphql-java-tools")
                options.contextClass -> args.add { environment -> environment.getContext() }
                else -> args.add { environment -> environment }
            }
        }

        return if (args.isEmpty() && isTrivialDataFetcher(this.method)) {
            TrivialMethodFieldResolverDataFetcher(getSourceResolver(), this.method, args, options)
        } else {
            MethodFieldResolverDataFetcher(getSourceResolver(), this.method, args, options)
        }
    }

    private fun parseInput(
        value: Any?,
        type: Type<*>,
        javaType: JavaType,
        environment: DataFetchingEnvironment
    ): Any? {
        return when (type) {
            is NonNullType -> parseInput(value, type.type, javaType, environment)
            is TypeName -> {
                if (javaType is ParameterizedType && Optional::class.isAssignableFrom(javaType.rawType)) {
                    // parse and wrap with optional
                    return Optional.ofNullable(parseInput(value, type, javaType.actualTypeArguments[0], environment))
                }

                when (val graphQLType = environment.graphQLSchema?.getType(type.name)) {
                    is GraphQLInputObjectType -> {
                        if (value == null) return value
                        return if ((javaType as Class<*>).constructors.any { it.parameters.isEmpty() }) {
                            parseInputObjectWithNoArgsConstructor(javaType, graphQLType, value, environment)
                        } else {
                            parseInputObjectWithAllArgsConstructor(javaType, graphQLType, value, environment)
                        }
                    }
                    is GraphQLScalarType -> when {
                        type.name == "ID" -> parseIdInput(value, javaType)
                        javaType is Class<*> && javaType.isPrimitive && value == null -> getPrimitiveDefault(javaType)
                        else -> value
                    }
                    is GraphQLEnumType -> when (value) {
                        is String -> (javaType as Class<*>)
                            .getMethod("valueOf", String::class.java)
                            .invoke(null, value)
                        else -> value
                    }
                    else -> value
                }
            }
            is ListType -> when {
                value == null -> value
                javaType is ParameterizedType && Optional::class.isAssignableFrom(javaType.rawType) -> {
                    // parse and wrap with optional
                    Optional.ofNullable(parseInput(value, type, javaType.actualTypeArguments[0], environment))
                }
                javaType is ParameterizedType && Collection::class.isAssignableFrom(javaType.rawType) -> {
                    val collection = (value as Collection<*>).map {
                        parseInput(it, type.type, javaType.actualTypeArguments[0], environment)
                    }

                    return when {
                        List::class.isAssignableFrom(javaType.rawType) -> collection.toList()
                        Set::class.isAssignableFrom(javaType.rawType) -> collection.toSet()
                        else -> collection
                    }
                }
                javaType is WildcardType -> parseInput(value, type, javaType.upperBounds[0], environment)
                else -> value
            }
            else -> value
        }
    }

    private fun parseInputObjectWithAllArgsConstructor(
        javaType: Class<*>,
        graphQLType: GraphQLInputObjectType,
        value: Any?,
        environment: DataFetchingEnvironment
    ): Any? {
        val fields = parseInputObjectFields(javaType, graphQLType, value, environment)

        return javaType
            .getDeclaredConstructor(*fields.map { it.first.type }.toTypedArray())
            .newInstance(*fields.map { it.second }.toTypedArray())
    }

    private fun parseInputObjectWithNoArgsConstructor(
        javaType: Class<*>,
        graphQLType: GraphQLInputObjectType,
        value: Any?,
        environment: DataFetchingEnvironment
    ): Any? {
        val inputObject = javaType.getDeclaredConstructor().newInstance()

        parseInputObjectFields(javaType, graphQLType, value, environment)
            .forEach {
                val field = it.first
                field.isAccessible = true
                field.set(inputObject, it.second)
            }

        return inputObject
    }

    private fun parseInputObjectFields(
        javaType: Class<*>,
        graphQLType: GraphQLInputObjectType,
        value: Any?,
        environment: DataFetchingEnvironment
    ): List<Pair<Field, Any?>> {
        return javaType.declaredFields
            .filterNot { it.isSynthetic }
            // TODO use an annotation specific to graphql (i.e. GraphQLIgnore?)
            .filterNot { it.isAnnotationPresent(JsonIgnore::class.java) }
            .map {
                val graphQLField = graphQLType.fields.find { t -> t.definition.name == it.name }
                    ?: throw IllegalArgumentException("Could not construct input object: missing field '${it.name}' in '${graphQLType.name}' ")
                val fieldValue = (value as Map<*, *>)[graphQLField.definition.name]
                val parsedValue = parseInput(fieldValue, graphQLField.definition.type, it.genericType, environment)
                Pair(it, parsedValue)
            }
    }

    private fun parseIdInput(value: Any?, javaType: JavaType) = when {
        value !is String // if value was already coerced
            || String::class.isAssignableFrom(javaType) -> value
        Int::class.isAssignableFrom(javaType)
            || Integer::class.isAssignableFrom(javaType) -> Integer.parseInt(value)
        Long::class.isAssignableFrom(javaType)
            || java.lang.Long::class.isAssignableFrom(javaType) -> java.lang.Long.parseLong(value)
        UUID::class.isAssignableFrom(javaType) -> UUID.fromString(value)
        else -> value
    }

    private fun KClass<*>.isAssignableFrom(javaType: JavaType): Boolean {
        return this.java.isAssignableFrom(javaType.unwrap())
    }

    private fun getPrimitiveDefault(javaType: Class<*>): Any? {
        return when (javaType) {
            Boolean::class.java -> false
            Byte::class.java -> 0
            Char::class.java -> '\u0000'
            Int::class.java -> 0
            Short::class.java -> 0
            Long::class.java -> 0L
            Float::class.java -> 0.0f
            Double::class.java -> 0.0
            else -> null
        }
    }

    override fun scanForMatches(): List<TypeClassMatcher.PotentialMatch> {
        val unwrappedGenericType = genericType.unwrapGenericType(try {
            method.kotlinFunction?.returnType?.javaType ?: method.genericReturnType
        } catch (e: InternalError) {
            method.genericReturnType
        })
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

internal open class MethodFieldResolverDataFetcher(
    private val sourceResolver: SourceResolver,
    method: Method,
    private val args: List<ArgumentPlaceholder>,
    private val options: SchemaParserOptions
) : DataFetcher<Any> {

    private val resolverMethod = method
    private val isSuspendFunction = try {
        method.kotlinFunction?.isSuspend == true
    } catch (e: InternalError) {
        false
    }

    private class CompareGenericWrappers {
        companion object : Comparator<GenericWrapper> {
            override fun compare(w1: GenericWrapper, w2: GenericWrapper): Int = when {
                w1.type.isAssignableFrom(w2.type) -> 1
                else -> -1
            }
        }
    }

    override fun get(environment: DataFetchingEnvironment): Any? {
        val source = sourceResolver(environment)
        val args = this.args.map { it(environment) }.toTypedArray()

        return if (isSuspendFunction) {
            environment.coroutineScope().future(options.coroutineContextProvider.provide()) {
                invokeSuspend(source, resolverMethod, args)?.transformWithGenericWrapper(environment)
            }
        } else {
            invoke(resolverMethod, source, args)?.transformWithGenericWrapper(environment)
        }
    }

    private fun Any.transformWithGenericWrapper(environment: DataFetchingEnvironment): Any? {
        return options.genericWrappers
            .asSequence()
            .filter { it.type.isInstance(this) }
            .sortedWith(CompareGenericWrappers)
            .firstOrNull()
            ?.transformer?.invoke(this, environment) ?: this
    }

    /**
     * Function that returns the object used to fetch the data.
     * It can be a DataFetcher or an entity.
     */
    @Suppress("unused")
    open fun getWrappedFetchingObject(environment: DataFetchingEnvironment): Any {
        return sourceResolver(environment)
    }
}

internal class TrivialMethodFieldResolverDataFetcher(
    sourceResolver: SourceResolver,
    method: Method,
    args: List<ArgumentPlaceholder>,
    options: SchemaParserOptions
) : MethodFieldResolverDataFetcher(sourceResolver, method, args, options),
    TrivialDataFetcher<Any> // just to mark it for tracing and optimizations

private suspend inline fun invokeSuspend(target: Any, resolverMethod: Method, args: Array<Any?>): Any? {
    return suspendCoroutineUninterceptedOrReturn { continuation ->
        invoke(resolverMethod, target, args + continuation)
    }
}

@Suppress("NOTHING_TO_INLINE")
private inline fun invoke(method: Method, instance: Any, args: Array<Any?>): Any? {
    try {
        return method.invoke(instance, *args)
    } catch (invocationException: java.lang.reflect.InvocationTargetException) {
        val e = invocationException.cause
        if (e is RuntimeException) {
            throw e
        }
        if (e is Error) {
            throw e
        }

        throw java.lang.reflect.UndeclaredThrowableException(e)
    }
}

internal typealias ArgumentPlaceholder = (DataFetchingEnvironment) -> Any?
