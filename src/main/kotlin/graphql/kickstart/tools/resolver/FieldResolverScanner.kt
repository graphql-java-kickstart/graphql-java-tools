package graphql.kickstart.tools.resolver

import graphql.GraphQLContext
import graphql.Scalars
import graphql.kickstart.tools.ResolverInfo
import graphql.kickstart.tools.RootResolverInfo
import graphql.kickstart.tools.SchemaParserOptions
import graphql.kickstart.tools.util.*
import graphql.language.FieldDefinition
import graphql.language.TypeName
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.channels.ReceiveChannel
import org.apache.commons.lang3.ClassUtils
import org.apache.commons.lang3.reflect.FieldUtils
import org.apache.commons.lang3.reflect.TypeUtils
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import java.lang.reflect.*
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.jvm.kotlinFunction

/**
 * @author Andrew Potter
 */
internal class FieldResolverScanner(val options: SchemaParserOptions) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val allowedLastArgumentTypes = listOfNotNull(DataFetchingEnvironment::class.java, GraphQLContext::class.java, options.contextClass)

    private val methodsByNameCache = mutableMapOf<Pair<Class<out Any>, Boolean>, Map<String, List<Method>>>()

    fun findFieldResolver(field: FieldDefinition, resolverInfo: ResolverInfo): FieldResolver {
        val searches = resolverInfo.getFieldSearches()

        val scanProperties = field.inputValueDefinitions.isEmpty()
        val found = searches.mapNotNull { search -> findFieldResolver(field, search, scanProperties) }

        if (resolverInfo is RootResolverInfo && found.size > 1) {
            throw FieldResolverError("Found more than one matching resolver for field '$field': $found")
        }

        return found.firstOrNull() ?: missingFieldResolver(field, searches, scanProperties)
    }

    private fun findFieldResolver(field: FieldDefinition, search: Search, scanProperties: Boolean): FieldResolver? {
        val method = findResolverMethod(field, search)
        if (method != null) {
            return MethodFieldResolver(field, search, options, method.apply(trySetAccessible(field, search.type)))
        }

        if (scanProperties) {
            val property = findResolverProperty(field, search)
            if (property != null) {
                return PropertyFieldResolver(field, search, options, property.apply(trySetAccessible(field, search.type)))
            }
        }

        if (java.util.Map::class.java.isAssignableFrom(search.type.unwrap())) {
            return MapFieldResolver(field, search, options, search.type.unwrap())
        }

        return null
    }

    private fun trySetAccessible(field: FieldDefinition, type: JavaType): AccessibleObject.() -> Unit = {
        try {
            isAccessible = true
        } catch (e: RuntimeException) {
            log.warn("Unable to make field ${type.unwrap().name}#${field.name} accessible. " +
                "Be sure to provide a resolver or open the enclosing module if possible.")
        }
    }

    private fun missingFieldResolver(field: FieldDefinition, searches: List<Search>, scanProperties: Boolean): FieldResolver {
        return if (options.allowUnimplementedResolvers
            || options.missingResolverDataFetcher != null
            || options.missingResolverDataFetcherProvider != null) {
            if (options.allowUnimplementedResolvers) {
                log.warn("Missing resolver for field: $field")
            }

            MissingFieldResolver(field, options)
        } else {
            throw FieldResolverError(getMissingFieldMessage(field, searches, scanProperties))
        }
    }

    private fun findResolverMethod(field: FieldDefinition, search: Search): Method? {
        val methodsByName = getMethodsByName(search)
        val argumentCount = field.inputValueDefinitions.size + if (search.requiredFirstParameterType != null) 1 else 0
        val name = field.name
        val capitalizedName = name.replaceFirstChar(Char::titlecase)

        // Check for the following one by one:
        //   1. Method with exact field name
        //   2. Method that returns a boolean with "is" style getter
        //   3. Method with "get" style getter
        //   4. Method with "getField" style getter
        //   5. Method with "get" style getter with the field name converted from snake_case to camelCased. ex: key_ops -> getKeyOps()
        return listOfNotNull(
            name,
            if (isBoolean(field.type)) "is$capitalizedName" else null,
            "get$capitalizedName",
            "getField$capitalizedName",
            "get${name.snakeToCamelCase()}"
        ).firstNotNullOfOrNull { methodName ->
            methodsByName[methodName]?.find { verifyMethodArguments(it, argumentCount, search) }
        }
    }

    // root fields are searched on every root resolver, so each class's methods are only indexed once
    private fun getMethodsByName(search: Search): Map<String, List<Method>> =
        methodsByNameCache.getOrPut(search.type.unwrap() to search.isSubscription) {
            getAllMethods(search).groupBy { it.name }
        }

    private fun getAllMethods(search: Search): List<Method> {
        val type = search.type.unwrap()
        val declaredMethods = type.declaredNonProxyMethods
        val superClassesMethods = ClassUtils.getAllSuperclasses(type).flatMap { it.methods.toList() }
        val interfacesMethods = ClassUtils.getAllInterfaces(type).flatMap { it.methods.toList() }

        return (declaredMethods + superClassesMethods + interfacesMethods)
            .asSequence()
            .filter { !it.isSynthetic }
            .filter { !Modifier.isPrivate(it.modifiers) }
            // discard any methods that are coming off the root of the class hierarchy
            // to avoid issues with duplicate method declarations
            .filter { it.declaringClass != Object::class.java }
            // subscription resolvers must return a publisher
            .filter { !search.isSubscription || resolverMethodReturnsPublisher(it) }
            .toList()
    }

    private fun resolverMethodReturnsPublisher(method: Method) =
        // suspend functions and unbounded type variables are erased to Object, so the actual return type is unknown here
        method.returnType == Any::class.java
            || Publisher::class.java.isAssignableFrom(method.returnType)
            || resolverMethodReturnsPublisherFuture(method)
            || receiveChannelToPublisherWrapper(method)

    private fun resolverMethodReturnsPublisherFuture(method: Method) =
        method.genericReturnType.futureValueType()
            ?.let { if (it is WildcardType) it.upperBounds.first() else it }
            ?.let { TypeUtils.isAssignable(it, Publisher::class.java) } == true

    private fun receiveChannelToPublisherWrapper(method: Method) =
        ReceiveChannel::class.java.isAssignableFrom(method.returnType)
            && options.genericWrappers.any { wrapper ->
            val isReceiveChannelWrapper = wrapper.type == method.returnType
            // lambdas are erased to return Object, so this only checks that the transformer can return a Publisher
            val hasPublisherTransformer = wrapper
                .transformer.javaClass
                .declaredMethods
                .filter { it.name == "invoke" }
                .any { it.returnType.isAssignableFrom(Publisher::class.java) }
            isReceiveChannelWrapper && hasPublisherTransformer
        }

    private fun isBoolean(type: GraphQLLangType) = type.unwrap().let { it is TypeName && it.name == Scalars.GraphQLBoolean.name }

    private fun verifyMethodArguments(method: Method, requiredCount: Int, search: Search): Boolean {
        val appropriateFirstParameter = if (search.requiredFirstParameterType != null) {
            method.genericParameterTypes.firstOrNull()?.let {
                it.eraseUnboundedWildcards() == search.requiredFirstParameterType || method.declaringClass.typeParameters.contains(it)
            } ?: false
        } else {
            // an extension receiver can only take the source object
            !isExtensionFunction(method)
        }

        val methodParameterCount = method.parameterCountWithoutContinuation()
        val methodLastParameter = method.parameterTypes.getOrNull(methodParameterCount - 1)

        val correctParameterCount = methodParameterCount == requiredCount ||
            (methodParameterCount == (requiredCount + 1) && allowedLastArgumentTypes.contains(methodLastParameter))
        return correctParameterCount && appropriateFirstParameter
    }

    private fun isExtensionFunction(method: Method): Boolean {
        return try {
            method.kotlinFunction?.extensionReceiverParameter != null
        } catch (e: InternalError) {
            false
        }
    }

    private fun findResolverProperty(field: FieldDefinition, search: Search) =
        FieldUtils.getAllFields(search.type.unwrap()).find { it.name == field.name }

    private fun getMissingFieldMessage(field: FieldDefinition, searches: List<Search>, scannedProperties: Boolean): String {
        val signatures = mutableListOf("")
        val isBoolean = isBoolean(field.type)
        var isSubscription = false

        searches.forEach { search ->
            signatures.addAll(getMissingMethodSignatures(field, search, isBoolean, scannedProperties))
            isSubscription = isSubscription || search.isSubscription
        }

        val sourceName = field.sourceLocation?.sourceName ?: "<unknown>"
        val sourceLocation = field.sourceLocation?.let { "$sourceName:${it.line}" } ?: "<unknown>"

        return "No method${if (scannedProperties) " or field" else ""} found as defined in schema $sourceLocation with any of the following signatures " +
            "(with or without one of $allowedLastArgumentTypes as the last argument), in priority order:\n${signatures.joinToString("\n  ")}" +
            if (isSubscription) "\n\nNote that a Subscription data fetcher must return a Publisher of events" else ""
    }

    private fun getMissingMethodSignatures(field: FieldDefinition, search: Search, isBoolean: Boolean, scannedProperties: Boolean): List<String> {
        val baseType = search.type.unwrap()
        val signatures = mutableListOf<String>()
        val args = mutableListOf<String>()
        val sep = ", "

        if (search.requiredFirstParameterType != null) {
            args.add(search.requiredFirstParameterType.name)
        }

        args.addAll(field.inputValueDefinitions.map { "~${it.name}" })

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

    data class Search(
        val type: JavaType,
        val resolverInfo: ResolverInfo,
        val source: Any?,
        val requiredFirstParameterType: Class<*>? = null
    ) {
        val isSubscription get() = resolverInfo is RootResolverInfo && resolverInfo.isSubscription
    }
}

internal class FieldResolverError(msg: String) : RuntimeException(msg)
