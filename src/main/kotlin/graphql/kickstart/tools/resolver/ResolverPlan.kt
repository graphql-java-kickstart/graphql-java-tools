package graphql.kickstart.tools.resolver

import com.fasterxml.classmate.TypeResolver
import graphql.GraphQLContext
import graphql.Scalars
import graphql.kickstart.tools.MissingResolverInfo
import graphql.kickstart.tools.ResolverError
import graphql.kickstart.tools.ResolverInfo
import graphql.kickstart.tools.ResolverSearch
import graphql.kickstart.tools.RootResolverInfo
import graphql.kickstart.tools.ScanDictionary
import graphql.kickstart.tools.SchemaParserOptions
import graphql.kickstart.tools.util.GraphQLLangType
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.coroutineScope
import graphql.kickstart.tools.util.eraseUnboundedWildcards
import graphql.kickstart.tools.util.snakeToCamelCase
import graphql.kickstart.tools.util.unwrap
import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.NonNullType
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.LightDataFetcher
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.TypeUtil
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.future
import org.apache.commons.lang3.ClassUtils
import org.apache.commons.lang3.reflect.FieldUtils
import org.apache.commons.lang3.reflect.TypeUtils
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.*
import java.util.function.Supplier
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.kotlinFunction

internal class ResolverPlan private constructor(
    val field: FieldDefinition,
    val search: ResolverSearch,
    private val options: SchemaParserOptions,
    private val member: Member?
) {
    val resolverInfo: ResolverInfo get() = search.resolverInfo

    // provided at parse time, so the user's provider doesn't run for a schema that fails to scan
    val objectMapper: ObjectMapper by lazy { options.objectMapperProvider.provide(field) }

    val argumentTargets: List<ArgumentTarget> get() = (member as? MethodMember)?.arguments.orEmpty()

    private val returnShape by lazy {
        member?.let { TypeShape.returnType(field.type, it.declaredValueType, search.isSubscription, search.type, options, it.reference) }
    }

    // each shape is walked only when the scanner gets to it, so an earlier match's dictionary error is reported first
    fun typeShapes(): Sequence<TypeShape> = sequence {
        (member as? MethodMember)?.let { yieldAll(it.parameterShapes()) }
        returnShape?.let { yield(it) }
    }

    fun dataFetcher(): DataFetcher<*> =
        member?.let(::fetcherOf) ?: (options.missingResolverDataFetcherProvider ?: notImplemented).createDataFetcher(field, options)

    private fun fetcherOf(member: Member): ResolverDataFetcher {
        val source = sourceResolver()
        val unwrapper = TypeShape.Unwrapper(returnShape!!, member.kind, member.name, member.isSuspend)
        return when (member) {
            is MethodMember -> {
                val slots = member.slots(objectMapper)
                if (slots.isNotEmpty() || member.isSuspend) {
                    MethodFieldResolverDataFetcher(source, member.method, slots, member.isSuspend, options, unwrapper)
                } else {
                    LightMethodFieldResolverDataFetcher(source, member.method, unwrapper)
                }
            }

            is PropertyMember -> PropertyFieldResolverDataFetcher(source, member.property, unwrapper)
            is MapMember -> MapFieldResolverDataFetcher(source, member.key, unwrapper)
        }
    }

    private fun sourceResolver(): SourceResolver {
        val resolver = search.source
        if (resolver != null) {
            return SourceResolver { _, _ -> resolver }
        }

        val type = search.type
        return SourceResolver { environment, sourceObject ->
            val source = sourceObject
                ?: environment?.getSource<Any>()
                ?: throw ResolverError("Expected DataFetchingEnvironment and source object to not be null!")

            if (!TypeUtils.isAssignable(source.javaClass, type)) {
                throw ResolverError("Expected source object to be an instance of '${TypeUtils.getRawType(type, type).name}' but instead got '${source.javaClass.name}'")
            }

            source
        }
    }

    fun notCallableOnResolver(objectType: String): NotCallableOnResolver? {
        val source = search.source ?: return null
        val javaMember = member?.javaMember ?: return null
        if (Modifier.isStatic(javaMember.modifiers) || javaMember.declaringClass.isInstance(source)) {
            return null
        }

        return NotCallableOnResolver(
            "Field '$objectType.${field.name}' resolves to ${javaMember.declaringClass.name}.${javaMember.name}, but the resolver is a " +
                "${source.javaClass.name}, which isn't a ${javaMember.declaringClass.name}, so every query of the field will fail.",
            javaMember is Field
        )
    }

    fun requireParameterForEachArgument(objectType: String) {
        val member = member as? MethodMember ?: return
        val method = member.method
        member.arguments.forEach { argument ->
            val parameter = TypeUtils.getRawType(argument.type, search.type) ?: return@forEach
            if (!ExtraParameter.takesArgumentSlot(parameter, argument.definition, options)) {
                return@forEach
            }

            throw FieldResolverError(
                "Field '$objectType.${field.name}' has no parameter for its argument '${argument.definition.name}: " +
                    "${TypeUtil.simplePrint(argument.definition.type)}' in ${method.declaringClass.name}.${method.name}, so the ${parameter.simpleName} " +
                    "parameter gets the argument instead. Add a parameter for each argument, in order, before the ${parameter.simpleName} parameter."
            )
        }
    }

    override fun toString() = member.toString()

    data class ArgumentTarget(val definition: InputValueDefinition, val type: JavaType)

    data class NotCallableOnResolver(val message: String, val isField: Boolean)

    class Finder(private val options: SchemaParserOptions, private val scalarNames: Set<String>) {

        // apps raise or silence these warnings by logger name in their logging config
        private val log = LoggerFactory.getLogger("graphql.kickstart.tools.resolver.FieldResolverScanner")

        private val methodsByNameCache = mutableMapOf<Pair<Class<out Any>, Boolean>, Map<String, List<Method>>>()

        fun find(field: FieldDefinition, resolverInfo: ResolverInfo): ResolverPlan {
            val searches = resolverInfo.getFieldSearches()

            val scanProperties = field.inputValueDefinitions.isEmpty()
            val found = searches.mapNotNull { search -> findMember(field, search, scanProperties)?.let { plan(field, search, it) } }

            if (resolverInfo is RootResolverInfo && found.size > 1) {
                throw FieldResolverError("Found more than one matching resolver for field '$field': $found")
            }

            return found.firstOrNull()
                ?: missingResolver(field)
                ?: findSourcelessResolver(field, searches)
                ?: throw FieldResolverError(getMissingFieldMessage(field, searches, scanProperties))
        }

        private fun plan(field: FieldDefinition, search: ResolverSearch, member: Member?) = ResolverPlan(field, search, options, member)

        private fun findMember(field: FieldDefinition, search: ResolverSearch, scanProperties: Boolean): Member? =
            ResolverKind.entries.firstNotNullOfOrNull { kind ->
                when (kind) {
                    ResolverKind.METHOD -> findResolverMethod(field, search)
                        ?.let { MethodMember(field, search, it.accessible(field, search.type), options, scalarNames) }

                    ResolverKind.FIELD -> if (scanProperties) {
                        findResolverProperty(field, search)?.let { PropertyMember(it.accessible(field, search.type)) }
                    } else {
                        null
                    }

                    ResolverKind.MAP_VALUE -> if (Map::class.java.isAssignableFrom(search.type.unwrap())) MapMember(field, search) else null
                }
            }

        private fun <T : AccessibleObject> T.accessible(field: FieldDefinition, type: JavaType): T = apply {
            try {
                isAccessible = true
            } catch (e: RuntimeException) {
                log.warn("Unable to make field ${type.unwrap().name}#${field.name} accessible. " +
                    "Be sure to provide a resolver or open the enclosing module if possible.")
            }
        }

        private fun missingResolver(field: FieldDefinition): ResolverPlan? {
            return if (options.allowUnimplementedResolvers || options.missingResolverDataFetcherProvider != null) {
                if (options.allowUnimplementedResolvers) {
                    log.warn("Missing resolver for field: $field")
                }

                plan(field, ResolverSearch(Any::class.java, MissingResolverInfo(), null), null)
            } else {
                null
            }
        }

        private fun findSourcelessResolver(field: FieldDefinition, searches: List<ResolverSearch>): ResolverPlan? =
            searches.firstNotNullOfOrNull { search ->
                val sourceType = search.requiredFirstParameterType ?: return@firstNotNullOfOrNull null
                val sourceless = search.withoutSourceParameter()
                findMethodByName(field, sourceless) { isSourcelessMethod(it, field, sourceless, sourceType) }?.let { method ->
                    plan(field, sourceless, MethodMember(field, sourceless, method.accessible(field, search.type), options, scalarNames))
                }
            }

        private fun isSourcelessMethod(method: Method, field: FieldDefinition, search: ResolverSearch, sourceType: Class<*>): Boolean {
            val arguments = field.inputValueDefinitions
            val parameterTypes = method.parameterTypes

            return method.parameterCountWithoutContinuation() > arguments.size &&
                arguments.indices.none { ExtraParameter.takesArgumentSlot(parameterTypes[it], arguments[it], options) } &&
                (arguments.isEmpty() || !couldTakeSource(parameterTypes[0], sourceType)) &&
                verifyMethodArguments(method, arguments.size, search)
        }

        private fun couldTakeSource(parameterType: Class<*>, sourceType: Class<*>) =
            sourceType.isAssignableFrom(parameterType) || parameterType.isAssignableFrom(sourceType)

        private fun findResolverMethod(field: FieldDefinition, search: ResolverSearch): Method? {
            val argumentCount = field.inputValueDefinitions.size + search.firstArgumentIndex
            return findMethodByName(field, search) { verifyMethodArguments(it, argumentCount, search) }
        }

        private inline fun findMethodByName(field: FieldDefinition, search: ResolverSearch, matches: (Method) -> Boolean): Method? {
            val methodsByName = getMethodsByName(search)
            val name = field.name
            val capitalizedName = name.replaceFirstChar(Char::titlecase)

            return listOfNotNull(
                name,
                if (isBoolean(field.type)) "is$capitalizedName" else null,
                "get$capitalizedName",
                "getField$capitalizedName",
                "get${name.snakeToCamelCase()}"
            ).firstNotNullOfOrNull { methodName ->
                methodsByName[methodName]?.find { matches(it) }
            }
        }

        private fun getMethodsByName(search: ResolverSearch): Map<String, List<Method>> =
            methodsByNameCache.getOrPut(search.type.unwrap() to search.isSubscription) {
                getAllMethods(search).groupBy { it.name }
            }

        private fun getAllMethods(search: ResolverSearch): List<Method> {
            val type = search.type.unwrap()
            val declaredMethods = if (Proxy.isProxyClass(type)) emptyList() else type.declaredMethods.toList()
            val superClassesMethods = ClassUtils.getAllSuperclasses(type).flatMap { it.methods.toList() }
            val interfacesMethods = ClassUtils.getAllInterfaces(type).flatMap { it.methods.toList() }

            return (declaredMethods + superClassesMethods + interfacesMethods)
                .asSequence()
                .filter { !it.isSynthetic }
                .filter { !Modifier.isPrivate(it.modifiers) }
                .filter { it.declaringClass != Any::class.java }
                .filter { !search.isSubscription || returnsPublisher(it.returnType, it.genericReturnType, options.genericWrappers) }
                .toList()
        }

        private fun isBoolean(type: GraphQLLangType) = type.unwrap().name == Scalars.GraphQLBoolean.name

        private fun verifyMethodArguments(method: Method, requiredCount: Int, search: ResolverSearch): Boolean {
            val appropriateFirstParameter = if (search.requiredFirstParameterType != null) {
                method.genericParameterTypes.firstOrNull()?.let {
                    it.eraseUnboundedWildcards() == search.requiredFirstParameterType || method.declaringClass.typeParameters.contains(it)
                } ?: false
            } else {
                !isExtensionFunction(method)
            }

            val methodParameterCount = method.parameterCountWithoutContinuation()

            val correctParameterCount = methodParameterCount >= requiredCount &&
                method.parameterTypes.slice(requiredCount until methodParameterCount).all { ExtraParameter.of(it, options) != null }
            return correctParameterCount && appropriateFirstParameter
        }

        private fun isExtensionFunction(method: Method): Boolean {
            return try {
                method.kotlinFunction?.extensionReceiverParameter != null
            } catch (e: InternalError) {
                false
            }
        }

        private fun findResolverProperty(field: FieldDefinition, search: ResolverSearch) =
            FieldUtils.getAllFields(search.type.unwrap()).find { it.name == field.name }

        private fun getMissingFieldMessage(field: FieldDefinition, searches: List<ResolverSearch>, scannedProperties: Boolean): String {
            val signatures = mutableListOf("")
            val isBoolean = isBoolean(field.type)
            var isSubscription = false

            searches.forEach { search ->
                signatures.addAll(getMissingMethodSignatures(field, search, isBoolean, scannedProperties))
                isSubscription = isSubscription || search.isSubscription
            }
            searches.filter { it.requiredFirstParameterType != null }.forEach { search ->
                signatures.addAll(getMissingMethodSignatures(field, search.withoutSourceParameter(), isBoolean, false, "<environment or context>"))
            }

            val sourceName = field.sourceLocation?.sourceName ?: "<unknown>"
            val sourceLocation = field.sourceLocation?.let { "$sourceName:${it.line}" } ?: "<unknown>"

            return "No method${if (scannedProperties) " or field" else ""} found as defined in schema $sourceLocation with any of the following signatures " +
                "(optionally followed by any of ${ExtraParameter.types(options)}), in priority order:\n${signatures.joinToString("\n  ")}" +
                if (isSubscription) "\n\nNote that a Subscription data fetcher must return a Publisher of events" else ""
        }

        private fun getMissingMethodSignatures(
            field: FieldDefinition,
            search: ResolverSearch,
            isBoolean: Boolean,
            scannedProperties: Boolean,
            trailingParameter: String? = null
        ): List<String> {
            val baseType = search.type.unwrap()
            val signatures = mutableListOf<String>()
            val args = mutableListOf<String>()
            val sep = ", "

            if (search.requiredFirstParameterType != null) {
                args.add(search.requiredFirstParameterType.name)
            }

            args.addAll(field.inputValueDefinitions.map { "~${it.name}" })
            trailingParameter?.let(args::add)

            val argString = args.joinToString(sep)

            signatures.add("${baseType.name}.${field.name}($argString)")
            if (isBoolean) {
                signatures.add("${baseType.name}.is${field.name.replaceFirstChar(Char::titlecase)}($argString)")
            }
            signatures.add("${baseType.name}.get${field.name.replaceFirstChar(Char::titlecase)}($argString)")
            if (scannedProperties) {
                signatures.add("${baseType.name}.${field.name}")
            }

            return signatures
        }
    }

    private companion object {
        val notImplemented = MissingResolverDataFetcherProvider { _, _ -> DataFetcher<Any> { TODO("Schema resolver not implemented") } }
    }
}

internal enum class ResolverKind(val noun: String, val holdCompletionStage: String, val unwrapsUntypedValues: Boolean = true) {
    METHOD("method", "Return a CompletionStage, such as a CompletableFuture, from the method or its generic wrapper transformer instead."),
    FIELD("field", "Assign a CompletionStage, such as a CompletableFuture, to the field, or return one from its generic wrapper transformer instead."),
    MAP_VALUE(
        "map value", "Put a CompletionStage, such as a CompletableFuture, in the map, or return one from its generic wrapper transformer instead.",
        unwrapsUntypedValues = false
    )
}

internal enum class ExtraParameter(private val bindingPrecedence: Int) {
    ENVIRONMENT(bindingPrecedence = 2) {
        override fun type(options: SchemaParserOptions): Class<*> = DataFetchingEnvironment::class.java
        override fun takesArgumentSlotOf(argument: InputValueDefinition) = true
        override fun bind(parameterType: Class<*>, method: Method): (DataFetchingEnvironment) -> Any? = { environment -> environment }
    },
    GRAPHQL_CONTEXT(bindingPrecedence = 0) {
        override fun type(options: SchemaParserOptions): Class<*> = GraphQLContext::class.java
        override fun takesArgumentSlotOf(argument: InputValueDefinition) = true
        override fun bind(parameterType: Class<*>, method: Method): (DataFetchingEnvironment) -> Any? = { environment -> environment.graphQlContext }
    },
    CONTEXT_CLASS(bindingPrecedence = 1) {
        override fun type(options: SchemaParserOptions): Class<*>? = options.contextClass

        // the context class can also be an argument's type (e.g. a Map for an input object), so only spec scalars count for it
        override fun takesArgumentSlotOf(argument: InputValueDefinition) =
            argument.type.unwrap().name in ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS

        override fun bind(parameterType: Class<*>, method: Method): (DataFetchingEnvironment) -> Any? = { environment ->
            environment.graphQlContext.get<Any>(parameterType) ?: throw ResolverError(
                "Resolver method '${method.declaringClass.name}.${method.name}' has a parameter of the context class " +
                    "'${parameterType.name}', but the GraphQLContext has no value for it. Put it in the GraphQLContext under " +
                    "the class key when building the ExecutionInput, for example " +
                    "graphQLContext(mapOf(${parameterType.simpleName}::class.java to context))."
            )
        }
    };

    abstract fun type(options: SchemaParserOptions): Class<*>?

    abstract fun takesArgumentSlotOf(argument: InputValueDefinition): Boolean

    abstract fun bind(parameterType: Class<*>, method: Method): (DataFetchingEnvironment) -> Any?

    companion object {
        private val byBindingPrecedence = entries.sortedBy { it.bindingPrecedence }

        fun types(options: SchemaParserOptions): List<Class<*>> = entries.mapNotNull { it.type(options) }

        fun of(parameterType: Class<*>, options: SchemaParserOptions): ExtraParameter? =
            byBindingPrecedence.find { it.type(options) == parameterType }

        fun takesArgumentSlot(parameterType: Class<*>, argument: InputValueDefinition, options: SchemaParserOptions): Boolean {
            val matching = entries.filter { it.type(options) == parameterType }
            return matching.isNotEmpty() && matching.all { it.takesArgumentSlotOf(argument) }
        }
    }
}

internal class FieldResolverError(msg: String) : RuntimeException(msg)

private sealed class Member(val kind: ResolverKind) {
    abstract val name: String
    abstract val declaredValueType: JavaType
    abstract val reference: ScanDictionary.Reference
    abstract val javaMember: java.lang.reflect.Member?
    open val isSuspend get() = false
}

private class MethodMember(
    field: FieldDefinition,
    private val search: ResolverSearch,
    val method: Method,
    private val options: SchemaParserOptions,
    private val scalarNames: Set<String>
) : Member(ResolverKind.METHOD) {
    override val isSuspend = method.isSuspendFunction()

    override val name get() = "${method.declaringClass.name}.${method.name}"
    override val javaMember get() = method
    override val reference get() = ScanDictionary.ReturnValueReference(method)

    override val declaredValueType: JavaType
        get() {
            val declaredType = try {
                method.kotlinFunction?.returnType?.javaType ?: method.genericReturnType
            } catch (e: InternalError) {
                method.genericReturnType
            }
            return resolveTypeVariables(declaredType, search.type)
        }

    val arguments: List<ResolverPlan.ArgumentTarget> = field.inputValueDefinitions.mapIndexed { index, definition ->
        ResolverPlan.ArgumentTarget(definition, resolveTypeVariables(method.genericParameterTypes[index + search.firstArgumentIndex], search.type))
    }

    fun parameterShapes(): Sequence<TypeShape> = arguments.asSequence().mapIndexed { index, (definition, type) ->
        TypeShape.parameterType(definition.type, type, search.type, options, ScanDictionary.MethodParameterReference(method, index))
    }

    fun slots(mapper: ObjectMapper): Array<(DataFetchingEnvironment) -> Any?> {
        val slots = mutableListOf<(DataFetchingEnvironment) -> Any?>()

        search.requiredFirstParameterType?.let { expectedType ->
            slots.add { environment ->
                val source = environment.getSource<Any>()
                    ?: throw ResolverError("Expected source object to not be null!")
                if (!expectedType.isAssignableFrom(source.javaClass)) {
                    throw ResolverError("Source type (${source.javaClass.name}) is not expected type (${expectedType.name})!")
                }

                source
            }
        }

        arguments.forEach { slots.add(argumentSlot(it, mapper)) }

        method.parameterTypes.slice(slots.size until method.parameterCountWithoutContinuation()).forEach { parameterType ->
            slots.add(ExtraParameter.of(parameterType, options)!!.bind(parameterType, method))
        }

        return slots.toTypedArray()
    }

    private fun argumentSlot(argument: ResolverPlan.ArgumentTarget, mapper: ObjectMapper): (DataFetchingEnvironment) -> Any? {
        val definition = argument.definition
        val isNonNull = definition.type is NonNullType
        val isOptional = isOptionalParameter(argument.type, search.type)
        val argumentConverter = ArgumentConverter(mapper, argument.type)
        val parameterClass = argument.type.unwrap()
        val isConcreteScalar = isConcreteScalarType(definition.type, argument.type, scalarNames)

        return slot@{ environment ->
            val argumentPresent = environment.arguments.containsKey(definition.name)
            if (!argumentPresent && isNonNull) {
                throw ResolverError("Missing required argument with name '${definition.name}', this is most likely a bug with graphql-java-tools")
            }

            val value = environment.arguments[definition.name]

            if (value == null && isOptional) {
                if (options.inputArgumentOptionalDetectOmission && !environment.containsArgument(definition.name)) {
                    return@slot null
                }
                return@slot Optional.empty<Any>()
            }

            if (value == null || !isConcreteScalar || !parameterClass.isInstance(value)) {
                return@slot argumentConverter.convert(value)
            }

            return@slot value
        }
    }

    // the build error for a field more than one root resolver serves lists the resolvers by these names
    override fun toString() = "MethodFieldResolver{method=$method}"
}

private class PropertyMember(val property: Field) : Member(ResolverKind.FIELD) {
    override val name get() = "${property.declaringClass.name}.${property.name}"
    override val javaMember get() = property
    override val declaredValueType: JavaType get() = property.genericType
    override val reference get() = ScanDictionary.FieldTypeReference(property.toString())

    override fun toString() = "PropertyFieldResolver{property=$property}"
}

private class MapMember(field: FieldDefinition, private val search: ResolverSearch) : Member(ResolverKind.MAP_VALUE) {
    val key: String = field.name

    override val declaredValueType: JavaType =
        TypeResolver().resolve(search.type.unwrap()).typeParametersFor(Map::class.java).elementAtOrElse(1) { Any::class.java }

    override val name get() = "${search.type.unwrap().name}.$key"
    override val javaMember: java.lang.reflect.Member? get() = null
    override val reference get() = ScanDictionary.FieldTypeReference(key)

    override fun toString() = "MapFieldResolver{key=$key}"
}

internal sealed class ResolverDataFetcher(protected val source: SourceResolver, protected val unwrapper: TypeShape.Unwrapper) : DataFetcher<Any?>

internal class MethodFieldResolverDataFetcher(
    source: SourceResolver,
    private val method: Method,
    private val slots: Array<(DataFetchingEnvironment) -> Any?>,
    private val isSuspend: Boolean,
    private val options: SchemaParserOptions,
    unwrapper: TypeShape.Unwrapper
) : ResolverDataFetcher(source, unwrapper) {

    override fun get(environment: DataFetchingEnvironment): Any? {
        val target = source.resolve(environment, null)
        val arguments = arrayOfNulls<Any?>(if (isSuspend) slots.size + 1 else slots.size)
        for (index in slots.indices) {
            arguments[index] = slots[index](environment)
        }

        return if (isSuspend) {
            // start undispatched so DataLoader loads are queued before graphql-java dispatches them,
            // which runs the block even if the context is already cancelled, hence ensureActive
            environment.coroutineScope().future(options.coroutineContextProvider.provide(), CoroutineStart.UNDISPATCHED) {
                ensureActive()
                invokeSuspending(method, target, arguments)?.let { unwrapper.unwrapSuspending(it) { environment } }
            }
        } else {
            invoke(method, target, arguments)?.let { unwrapper.unwrap(it) { environment } }
        }
    }

    /**
     * The object the method is called on, the resolver or the source object. Apps call this by reflection from graphql-java's
     * `Instrumentation.instrumentDataFetcher` to reach the resolver behind a field.
     */
    fun getWrappedFetchingObject(environment: DataFetchingEnvironment): Any = source.resolve(environment, null)
}

internal class LightMethodFieldResolverDataFetcher(
    source: SourceResolver,
    private val method: Method,
    unwrapper: TypeShape.Unwrapper
) : ResolverDataFetcher(source, unwrapper), LightDataFetcher<Any?> {

    override fun get(fieldDefinition: GraphQLFieldDefinition?, sourceObject: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? =
        invoke(method, source.resolve(null, sourceObject), noArguments)?.let { unwrapper.unwrap(it, environmentSupplier) }

    override fun get(environment: DataFetchingEnvironment): Any? =
        get(environment.fieldDefinition, source.resolve(environment, null)) { environment }

    /**
     * See [MethodFieldResolverDataFetcher.getWrappedFetchingObject].
     */
    fun getWrappedFetchingObject(environment: DataFetchingEnvironment): Any = source.resolve(environment, null)
}

internal class PropertyFieldResolverDataFetcher(
    source: SourceResolver,
    private val property: Field,
    unwrapper: TypeShape.Unwrapper
) : ResolverDataFetcher(source, unwrapper), LightDataFetcher<Any?> {

    override fun get(fieldDefinition: GraphQLFieldDefinition?, sourceObject: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? =
        property.get(source.resolve(null, sourceObject))?.let { unwrapper.unwrap(it, environmentSupplier) }

    override fun get(environment: DataFetchingEnvironment): Any? =
        get(environment.fieldDefinition, source.resolve(environment, null)) { environment }
}

internal class MapFieldResolverDataFetcher(
    source: SourceResolver,
    private val key: String,
    unwrapper: TypeShape.Unwrapper
) : ResolverDataFetcher(source, unwrapper), LightDataFetcher<Any?> {

    override fun get(fieldDefinition: GraphQLFieldDefinition?, sourceObject: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? {
        val map = sourceObject as? Map<*, *> ?: throw RuntimeException("MapFieldResolver attempt to fetch a field from an object instance that was not a map")
        return map[key]?.let { unwrapper.unwrap(it, environmentSupplier) }
    }

    override fun get(environment: DataFetchingEnvironment): Any? =
        get(environment.fieldDefinition, source.resolve(environment, null)) { environment }
}

private val noArguments = emptyArray<Any?>()

private suspend fun invokeSuspending(method: Method, instance: Any, arguments: Array<Any?>): Any? =
    suspendCoroutineUninterceptedOrReturn { continuation ->
        arguments[arguments.lastIndex] = continuation
        invoke(method, instance, arguments)
    }

private fun invoke(method: Method, instance: Any, arguments: Array<Any?>): Any? {
    try {
        return method.invoke(instance, *arguments)
    } catch (e: InvocationTargetException) {
        throw e.cause ?: RuntimeException("Unknown error occurred while invoking resolver method")
    }
}

private fun Method.isSuspendFunction(): Boolean {
    return try {
        this.kotlinFunction?.isSuspend == true
    } catch (e: InternalError) {
        false
    }
}

private fun Method.parameterCountWithoutContinuation(): Int =
    if (isSuspendFunction()) parameterCount - 1 else parameterCount
