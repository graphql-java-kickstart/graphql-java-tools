package com.coxautodev.graphql.tools

import com.coxautodev.graphql.tools.relay.RelayConnectionFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.BiMap
import com.google.common.collect.HashBiMap
import com.google.common.collect.Maps
import graphql.language.Definition
import graphql.language.Document
import graphql.parser.Parser
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaDirectiveWiring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.reactive.publish
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.reactivestreams.Publisher
import sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * @author Andrew Potter
 */
class SchemaParserBuilder constructor(private val dictionary: SchemaParserDictionary = SchemaParserDictionary()) {

    private val schemaString = StringBuilder()
    private val files = mutableListOf<String>()
    private val resolvers = mutableListOf<GraphQLResolver<*>>()
    private val scalars = mutableListOf<GraphQLScalarType>()
    private val runtimeWiringBuilder = RuntimeWiring.newRuntimeWiring()
    private var options = SchemaParserOptions.defaultOptions()

    /**
     * Add GraphQL schema files from the classpath.
     */
    fun files(vararg files: String) = this.apply {
        files.forEach { this.file(it) }
    }

    /**
     * Add a GraphQL Schema file from the classpath.
     */
    fun file(filename: String) = this.apply {
        files.add(filename)
    }

    /**
     * Add a GraphQL schema string directly.
     */
    fun schemaString(string: String) = this.apply {
        if (!schemaString.isEmpty()) {
            schemaString.append("\n")
        }
        schemaString.append(string)
    }

    /**
     * Add GraphQLResolvers to the parser's dictionary.
     */
    fun resolvers(vararg resolvers: GraphQLResolver<*>) = this.apply {
        this.resolvers.addAll(resolvers)
    }

    /**
     * Add GraphQLResolvers to the parser's dictionary.
     */
    fun resolvers(resolvers: List<GraphQLResolver<*>>) = this.apply {
        this.resolvers.addAll(resolvers)
    }

    /**
     * Add scalars to the parser's dictionary.
     */
    fun scalars(vararg scalars: GraphQLScalarType) = this.apply {
        this.scalars.addAll(scalars)
    }

    /**
     * Add scalars to the parser's dictionary.
     */
    fun scalars(scalars: List<GraphQLScalarType>) = this.apply {
        this.scalars.addAll(scalars)
    }

    fun directive(name: String, directive: SchemaDirectiveWiring) = this.apply {
        this.runtimeWiringBuilder.directive(name, directive)
    }

    /**
     * Add arbitrary classes to the parser's dictionary, overriding the generated type name.
     */
    fun dictionary(name: String, clazz: Class<*>) = this.apply {
        this.dictionary.add(name, clazz)
    }

    /**
     * Add arbitrary classes to the parser's dictionary, overriding the generated type name.
     */
    fun dictionary(name: String, clazz: KClass<*>) = this.apply {
        this.dictionary.add(name, clazz)
    }

    /**
     * Add arbitrary classes to the parser's dictionary, overriding the generated type name.
     */
    fun dictionary(dictionary: Map<String, Class<*>>) = this.apply {
        this.dictionary.add(dictionary)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun dictionary(clazz: Class<*>) = this.apply {
        this.dictionary.add(clazz)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun dictionary(clazz: KClass<*>) = this.apply {
        this.dictionary.add(clazz)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun dictionary(vararg dictionary: Class<*>) = this.apply {
        this.dictionary.add(*dictionary)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun dictionary(vararg dictionary: KClass<*>) = this.apply {
        this.dictionary.add(*dictionary)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun dictionary(dictionary: Collection<Class<*>>) = this.apply {
        this.dictionary.add(dictionary)
    }

    fun options(options: SchemaParserOptions) = this.apply {
        this.options = options
    }

    /**
     * Scan for classes with the supplied schema and dictionary.  Used for testing.
     */
    private fun scan(): ScannedSchemaObjects {
        val definitions = appendDynamicDefinitions(parseDefinitions())
        val customScalars = scalars.associateBy { it.name }

        return SchemaClassScanner(dictionary.getDictionary(), definitions, resolvers, customScalars, options)
                .scanForClasses()
    }

    private fun parseDefinitions() = parseDocuments().flatMap { it.definitions }

    private fun appendDynamicDefinitions(baseDefinitions: List<Definition<*>>): List<Definition<*>> {
        val definitions = baseDefinitions.toMutableList()
        options.typeDefinitionFactories.forEach { definitions.addAll(it.create(definitions)) }
        return definitions.toList()
    }

    private fun parseDocuments(): List<Document> {
        val parser = Parser()
        val documents = mutableListOf<Document>()
        try {
            files.forEach { documents.add(parser.parseDocument(readFile(it), it)) }

            if (!schemaString.isEmpty()) {
                documents.add(parser.parseDocument(this.schemaString.toString()))
            }
        } catch (pce: ParseCancellationException) {
            val cause = pce.cause
            if (cause != null && cause is RecognitionException) {
                throw InvalidSchemaError(pce, cause)
            } else {
                throw pce
            }
        }
        return documents
    }

    private fun readFile(filename: String): String {
        return java.io.BufferedReader(java.io.InputStreamReader(
                object : Any() {}.javaClass.classLoader.getResourceAsStream(filename)
                        ?: throw java.io.FileNotFoundException("classpath:$filename")
        )).readText()
    }

    /**
     * Build the parser with the supplied schema and dictionary.
     */
    fun build() = SchemaParser(scan(), options, runtimeWiringBuilder.build())
}

class InvalidSchemaError(pce: ParseCancellationException, private val recognitionException: RecognitionException) : RuntimeException(pce) {
    override val message: String?
        get() = "Invalid schema provided (${recognitionException.javaClass.name}) at: ${recognitionException.offendingToken}"
}

class SchemaParserDictionary {

    private val dictionary: BiMap<String, Class<*>> = HashBiMap.create()

    fun getDictionary(): BiMap<String, Class<*>> = Maps.unmodifiableBiMap(dictionary)

    /**
     * Add arbitrary classes to the parser's dictionary, overriding the generated type name.
     */
    fun add(name: String, clazz: Class<*>) = this.apply {
        this.dictionary.put(name, clazz)
    }

    /**
     * Add arbitrary classes to the parser's dictionary, overriding the generated type name.
     */
    fun add(name: String, clazz: KClass<*>) = this.apply {
        this.dictionary.put(name, clazz.java)
    }

    /**
     * Add arbitrary classes to the parser's dictionary, overriding the generated type name.
     */
    fun add(dictionary: Map<String, Class<*>>) = this.apply {
        this.dictionary.putAll(dictionary)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun add(clazz: Class<*>) = this.apply {
        this.add(clazz.simpleName, clazz)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun add(clazz: KClass<*>) = this.apply {
        this.add(clazz.java.simpleName, clazz)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun add(vararg dictionary: Class<*>) = this.apply {
        dictionary.forEach { this.add(it) }
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun add(vararg dictionary: KClass<*>) = this.apply {
        dictionary.forEach { this.add(it) }
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun add(dictionary: Collection<Class<*>>) = this.apply {
        dictionary.forEach { this.add(it) }
    }
}

data class SchemaParserOptions internal constructor(
        val contextClass: Class<*>?,
        val genericWrappers: List<GenericWrapper>,
        val allowUnimplementedResolvers: Boolean,
        val objectMapperProvider: PerFieldObjectMapperProvider,
        val proxyHandlers: List<ProxyHandler>,
        val preferGraphQLResolver: Boolean,
        val introspectionEnabled: Boolean,
        val coroutineContextProvider: CoroutineContextProvider,
        val typeDefinitionFactories: List<TypeDefinitionFactory>
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
        private var objectMapperProvider: PerFieldObjectMapperProvider = PerFieldConfiguringObjectMapperProvider()
        private val proxyHandlers: MutableList<ProxyHandler> = mutableListOf(Spring4AopProxyHandler(), GuiceAopProxyHandler(), JavassistProxyHandler(), WeldProxyHandler())
        private var preferGraphQLResolver = false
        private var introspectionEnabled = true
        private var coroutineContextProvider: CoroutineContextProvider? = null
        private var coroutineContext: CoroutineContext? = null
        private var typeDefinitionFactories: MutableList<TypeDefinitionFactory> = mutableListOf(RelayConnectionFactory())

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

        fun preferGraphQLResolver(preferGraphQLResolver: Boolean) = this.apply {
            this.preferGraphQLResolver = preferGraphQLResolver
        }

        fun objectMapperConfigurer(objectMapperConfigurer: ObjectMapperConfigurer) = this.apply {
            this.objectMapperProvider = PerFieldConfiguringObjectMapperProvider(objectMapperConfigurer)
        }

        fun objectMapperProvider(objectMapperProvider: PerFieldObjectMapperProvider) = this.apply {
            this.objectMapperProvider = objectMapperProvider
        }

        fun objectMapperConfigurer(objectMapperConfigurer: (ObjectMapper, ObjectMapperConfigurerContext) -> Unit) = this.apply {
            this.objectMapperConfigurer(ObjectMapperConfigurer(objectMapperConfigurer))
        }

        fun addProxyHandler(proxyHandler: ProxyHandler) = this.apply {
            this.proxyHandlers.add(proxyHandler)
        }

        fun introspectionEnabled(introspectionEnabled: Boolean) = this.apply {
            this.introspectionEnabled = introspectionEnabled
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

        @ExperimentalCoroutinesApi
        fun build(): SchemaParserOptions {
            val coroutineContextProvider = coroutineContextProvider ?: DefaultCoroutineContextProvider(Dispatchers.Default)
            val wrappers = if (useDefaultGenericWrappers) {
                genericWrappers + listOf(
                        GenericWrapper(Future::class, 0),
                        GenericWrapper(CompletableFuture::class, 0),
                        GenericWrapper(CompletionStage::class, 0),
                        GenericWrapper(Publisher::class, 0),
                        GenericWrapper.withTransformer(ReceiveChannel::class, 0, { receiveChannel ->
                            GlobalScope.publish(coroutineContextProvider.provide()) {
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

            return SchemaParserOptions(contextClass, wrappers, allowUnimplementedResolvers, objectMapperProvider,
                    proxyHandlers, preferGraphQLResolver, introspectionEnabled, coroutineContextProvider, typeDefinitionFactories)
        }
    }

    internal class DefaultCoroutineContextProvider(val coroutineContext: CoroutineContext): CoroutineContextProvider {
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
                return withTransformer(
                        type,
                        index,
                        transformer,
                        { innerType -> ParameterizedTypeImpl.make(List::class.java, arrayOf(innerType), null) }
                )
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
