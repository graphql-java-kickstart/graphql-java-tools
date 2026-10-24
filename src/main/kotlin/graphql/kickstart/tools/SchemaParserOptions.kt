package graphql.kickstart.tools

import graphql.kickstart.tools.proxy.*
import graphql.kickstart.tools.relay.RelayConnectionFactory
import graphql.kickstart.tools.resolver.MissingResolverDataFetcherProvider
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.ParameterizedTypeImpl
import graphql.schema.DataFetchingEnvironment
import graphql.schema.visibility.GraphqlFieldVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.reactive.publish
import org.reactivestreams.Publisher
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

@ExposedCopyVisibility
data class SchemaParserOptions internal constructor(
    val contextClass: Class<*>?,
    val genericWrappers: List<GenericWrapper>,
    val allowUnimplementedResolvers: Boolean,
    val missingResolverDataFetcherProvider: MissingResolverDataFetcherProvider?,
    val objectMapperProvider: PerFieldObjectMapperProvider,
    val proxyHandlers: List<ProxyHandler>,
    val inputArgumentOptionalDetectOmission: Boolean,
    val preferGraphQLResolver: Boolean,
    val coroutineContextProvider: CoroutineContextProvider,
    val typeDefinitionFactories: List<TypeDefinitionFactory>,
    val fieldVisibility: GraphqlFieldVisibility?,
    val includeUnusedTypes: Boolean,
    val useCommentsForDescriptions: Boolean,
    val allowUndeclaredDirectives: Boolean,
    val unmappedInputFields: UnmappedInputFields
) {
    companion object {
        @JvmStatic
        fun newOptions() = Builder()

        @JvmStatic
        fun defaultOptions() = Builder().build()
    }

    val coroutineContext: CoroutineContext
        get() = coroutineContextProvider.provide()

    class Builder {
        private var contextClass: Class<*>? = null
        private val genericWrappers: MutableList<GenericWrapper> = mutableListOf()
        private var useDefaultGenericWrappers = true
        private var allowUnimplementedResolvers = false
        private var missingResolverDataFetcherProvider: MissingResolverDataFetcherProvider? = null
        private var objectMapperProvider: PerFieldObjectMapperProvider = DefaultObjectMapperProvider()
        private val proxyHandlers: MutableList<ProxyHandler> = mutableListOf(Spring4AopProxyHandler(), GuiceAopProxyHandler(), JavassistProxyHandler(), WeldProxyHandler())
        private var inputArgumentOptionalDetectOmission = false
        private var preferGraphQLResolver = false
        private var coroutineContextProvider: CoroutineContextProvider? = null
        private var typeDefinitionFactories: MutableList<TypeDefinitionFactory> = mutableListOf(RelayConnectionFactory())
        private var fieldVisibility: GraphqlFieldVisibility? = null
        private var includeUnusedTypes = false
        private var useCommentsForDescriptions = true
        private var allowUndeclaredDirectives = false
        private var unmappedInputFields = UnmappedInputFields.WARN

        fun contextClass(contextClass: Class<*>) = this.apply {
            this.contextClass = contextClass
        }

        fun contextClass(contextClass: KClass<*>) = this.apply {
            this.contextClass = contextClass.java
        }

        fun genericWrappers(genericWrappers: List<GenericWrapper>) = this.apply {
            this.genericWrappers.addAll(genericWrappers)
        }

        fun genericWrappers(vararg genericWrappers: GenericWrapper) = this.apply {
            this.genericWrappers.addAll(genericWrappers)
        }

        fun useDefaultGenericWrappers(useDefaultGenericWrappers: Boolean) = this.apply {
            this.useDefaultGenericWrappers = useDefaultGenericWrappers
        }

        fun allowUnimplementedResolvers(allowUnimplementedResolvers: Boolean) = this.apply {
            this.allowUnimplementedResolvers = allowUnimplementedResolvers
        }

        fun missingResolverDataFetcherProvider(missingResolverDataFetcherProvider: MissingResolverDataFetcherProvider?) = this.apply {
            this.missingResolverDataFetcherProvider = missingResolverDataFetcherProvider
        }

        fun inputArgumentOptionalDetectOmission(inputArgumentOptionalDetectOmission: Boolean) = this.apply {
            this.inputArgumentOptionalDetectOmission = inputArgumentOptionalDetectOmission
        }

        fun preferGraphQLResolver(preferGraphQLResolver: Boolean) = this.apply {
            this.preferGraphQLResolver = preferGraphQLResolver
        }

        /**
         * Customizes the ObjectMapper of each resolver field, starting from the library's defaults and the Kotlin module.
         * Replaces a provider set before, and the other way round.
         */
        fun objectMapperConfigurer(objectMapperConfigurer: ObjectMapperConfigurer) = this.apply {
            this.objectMapperProvider = DefaultObjectMapperProvider(objectMapperConfigurer)
        }

        /**
         * Uses these ObjectMappers as they are, e.g. `objectMapperProvider { springMapper }` to share an application's
         * JsonMapper. They don't get the library's defaults or the Kotlin module. For a per-field variant of your own mapper,
         * return `mapper.rebuild()...build()` from the provider.
         */
        fun objectMapperProvider(objectMapperProvider: PerFieldObjectMapperProvider) = this.apply {
            this.objectMapperProvider = objectMapperProvider
        }

        fun addProxyHandler(proxyHandler: ProxyHandler) = this.apply {
            this.proxyHandlers.add(proxyHandler)
        }

        fun coroutineContext(context: CoroutineContext) = this.apply {
            this.coroutineContextProvider = DefaultCoroutineContextProvider(context)
        }

        fun coroutineContextProvider(contextProvider: CoroutineContextProvider) = this.apply {
            this.coroutineContextProvider = contextProvider
        }

        fun typeDefinitionFactory(factory: TypeDefinitionFactory) = this.apply {
            this.typeDefinitionFactories.add(factory)
        }

        fun fieldVisibility(fieldVisibility: GraphqlFieldVisibility) = this.apply {
            this.fieldVisibility = fieldVisibility
        }

        fun includeUnusedTypes(includeUnusedTypes: Boolean) = this.apply {
            this.includeUnusedTypes = includeUnusedTypes
        }

        fun useCommentsForDescriptions(useCommentsForDescriptions: Boolean) = this.apply {
            this.useCommentsForDescriptions = useCommentsForDescriptions
        }

        /**
         * Allows applying directives that have no definition in the schema, e.g. the Apollo Federation directives imported through `@link`.
         * They're only added as applied directives, with argument types guessed from their values.
         */
        fun allowUndeclaredDirectives(allowUndeclaredDirectives: Boolean) = this.apply {
            this.allowUndeclaredDirectives = allowUndeclaredDirectives
        }

        /**
         * What to do about input object fields that the class of a resolver argument has no property for. With the library's
         * ObjectMapper, converting the argument fails when such a field is sent. An ObjectMapper that ignores unknown
         * properties, the Jackson 3 default and Spring's, drops the field's value instead. Defaults to [UnmappedInputFields.WARN].
         *
         * The classes are checked when the schema is built, with the deserializer of each resolver field's own ObjectMapper, so
         * anything that maps a property for Jackson counts, e.g. setters, creators, naming strategies, aliases, ignored properties
         * and any setters. A field is still reported if the ObjectMapper ignores unknown properties, since that's usually set for
         * every class at once, but not if its class has `@JsonIgnoreProperties(ignoreUnknown = true)`.
         *
         * Classes that Jackson doesn't deserialize property by property aren't checked: those with a custom deserializer, a
         * delegating creator, a builder or type info, and those with injected values. Neither are arguments bound to a Map, a
         * JsonNode or a type variable.
         */
        fun unmappedInputFields(unmappedInputFields: UnmappedInputFields) = this.apply {
            this.unmappedInputFields = unmappedInputFields
        }

        fun build(): SchemaParserOptions {
            val coroutineContextProvider = coroutineContextProvider
                ?: DefaultCoroutineContextProvider(Dispatchers.Default)
            val wrappers = if (useDefaultGenericWrappers) {
                genericWrappers + listOf(
                    GenericWrapper(Future::class, 0),
                    GenericWrapper(CompletableFuture::class, 0),
                    GenericWrapper(CompletionStage::class, 0),
                    GenericWrapper(Publisher::class, 0),
                    GenericWrapper.withTransformer(ReceiveChannel::class, 0, { receiveChannel ->
                        publish(coroutineContextProvider.provide()) {
                            try {
                                for (item in receiveChannel) {
                                    send(item)
                                }
                            } finally {
                                receiveChannel.cancel()
                            }
                        }
                    })
                )
            } else {
                genericWrappers
            }

            return SchemaParserOptions(
                contextClass,
                wrappers,
                allowUnimplementedResolvers,
                missingResolverDataFetcherProvider,
                objectMapperProvider,
                proxyHandlers,
                inputArgumentOptionalDetectOmission,
                preferGraphQLResolver,
                coroutineContextProvider,
                typeDefinitionFactories,
                fieldVisibility,
                includeUnusedTypes,
                useCommentsForDescriptions,
                allowUndeclaredDirectives,
                unmappedInputFields
            )
        }
    }

    internal class DefaultCoroutineContextProvider(private val coroutineContext: CoroutineContext) : CoroutineContextProvider {
        override fun provide(): CoroutineContext {
            return coroutineContext
        }
    }

    data class GenericWrapper(
        val type: Class<*>,
        val index: Int,
        val transformer: (Any, DataFetchingEnvironment) -> Any? = { x, _ -> x },
        val schemaWrapper: (JavaType) -> JavaType = { x -> x }
    ) {
        constructor(type: Class<*>, index: Int) : this(type, index, { x, _ -> x })
        constructor(type: KClass<*>, index: Int) : this(type.java, index, { x, _ -> x })

        companion object {
            @Suppress("UNCHECKED_CAST")
            @JvmStatic
            fun <T> withTransformer(
                type: Class<T>,
                index: Int,
                transformer: (T, DataFetchingEnvironment) -> Any?,
                schemaWrapper: (JavaType) -> JavaType = { x -> x }
            ): GenericWrapper where T : Any {
                return GenericWrapper(type, index, transformer as (Any, DataFetchingEnvironment) -> Any?, schemaWrapper)
            }

            fun <T> withTransformer(
                type: KClass<T>,
                index: Int,
                transformer: (T, DataFetchingEnvironment) -> Any?,
                schemaWrapper: (JavaType) -> JavaType = { x -> x }
            ): GenericWrapper where T : Any {
                return withTransformer(type.java, index, transformer, schemaWrapper)
            }

            @JvmStatic
            fun <T> withTransformer(
                type: Class<T>,
                index: Int,
                transformer: (T) -> Any?,
                schemaWrapper: (JavaType) -> JavaType = { x -> x }
            ): GenericWrapper where T : Any {
                return withTransformer(type, index, { x, _ -> transformer.invoke(x) }, schemaWrapper)
            }

            fun <T> withTransformer(
                type: KClass<T>,
                index: Int,
                transformer: (T) -> Any?,
                schemaWrapper: (JavaType) -> JavaType = { x -> x }
            ): GenericWrapper where T : Any {
                return withTransformer(type.java, index, transformer, schemaWrapper)
            }

            @JvmStatic
            fun <T> listCollectionWithTransformer(
                type: Class<T>,
                index: Int,
                transformer: (T) -> Any?
            ): GenericWrapper where T : Any {
                return withTransformer(type, index, transformer) { innerType ->
                    ParameterizedTypeImpl(List::class.java, arrayOf(innerType), null)
                }
            }

            fun <T> listCollectionWithTransformer(
                type: KClass<T>,
                index: Int,
                transformer: (T) -> Any?
            ): GenericWrapper where T : Any {
                return listCollectionWithTransformer(type.java, index, transformer)
            }
        }
    }
}
