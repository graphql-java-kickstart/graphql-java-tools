package com.coxautodev.graphql.tools

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.BiMap
import com.google.common.collect.HashBiMap
import com.google.common.collect.Maps
import graphql.parser.Parser
import graphql.schema.GraphQLScalarType
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.reactivestreams.Publisher
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future
import kotlin.reflect.KClass

/**
 * @author Andrew Potter
 */
class SchemaParserBuilder constructor(private val dictionary: SchemaParserDictionary = SchemaParserDictionary()) {

    private val schemaString = StringBuilder()
    private val resolvers = mutableListOf<GraphQLResolver<*>>()
    private val scalars = mutableListOf<GraphQLScalarType>()
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
        this.schemaString(java.io.BufferedReader(java.io.InputStreamReader(
            object : Any() {}.javaClass.classLoader.getResourceAsStream(filename) ?: throw java.io.FileNotFoundException("classpath:$filename")
        )).readText())
    }

    /**
     * Add a GraphQL schema string directly.
     */
    fun schemaString(string: String) = this.apply {
        schemaString.append("\n").append(string)
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
        val document = try {
            Parser().parseDocument(this.schemaString.toString())
        } catch (pce: ParseCancellationException) {
            val cause = pce.cause
            if(cause != null && cause is RecognitionException) {
                throw InvalidSchemaError(pce, cause)
            } else {
                throw pce
            }
        }

        val definitions = document.definitions
        val customScalars = scalars.associateBy { it.name }

        return SchemaClassScanner(dictionary.getDictionary(), definitions, resolvers, customScalars, options).scanForClasses()
    }

    /**
     * Build the parser with the supplied schema and dictionary.
     */
    fun build() = SchemaParser(scan())
}

class InvalidSchemaError(pce: ParseCancellationException, private val recognitionException: RecognitionException): RuntimeException(pce) {
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

data class SchemaParserOptions internal constructor(val contextClass: Class<*>?, val genericWrappers: List<GenericWrapper>, val allowUnimplementedResolvers: Boolean, val objectMapperConfigurer: ObjectMapperConfigurer, val proxyHandlers: List<ProxyHandler>) {
    companion object {
        @JvmStatic fun newOptions() = Builder()
        @JvmStatic fun defaultOptions() = Builder().build()
    }

    class Builder {
        private var contextClass: Class<*>? = null
        private val genericWrappers: MutableList<GenericWrapper> = mutableListOf()
        private var useDefaultGenericWrappers = true
        private var allowUnimplementedResolvers = false
        private var objectMapperConfigurer: ObjectMapperConfigurer = ObjectMapperConfigurer { _, _ ->  }
        private val proxyHandlers: MutableList<ProxyHandler> = mutableListOf(Spring4AopProxyHandler(), GuiceAopProxyHandler())

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

        fun objectMapperConfigurer(objectMapperConfigurer: ObjectMapperConfigurer) = this.apply {
            this.objectMapperConfigurer = objectMapperConfigurer
        }

        fun objectMapperConfigurer(objectMapperConfigurer: (ObjectMapper, ObjectMapperConfigurerContext) -> Unit) = this.apply {
            this.objectMapperConfigurer(ObjectMapperConfigurer(objectMapperConfigurer))
        }

        fun addProxyHandler(proxyHandler: ProxyHandler) = this.apply {
            this.proxyHandlers.add(proxyHandler)
        }

        fun build(): SchemaParserOptions {
            val wrappers = if(useDefaultGenericWrappers) {
                genericWrappers + listOf(
                    GenericWrapper(Future::class, 0),
                    GenericWrapper(CompletableFuture::class, 0),
                    GenericWrapper(CompletionStage::class, 0),
                    GenericWrapper(Publisher::class, 0)
                )
            } else {
                genericWrappers
            }

            return SchemaParserOptions(contextClass, wrappers, allowUnimplementedResolvers, objectMapperConfigurer, proxyHandlers)
        }
    }

    data class GenericWrapper(val type: Class<*>, val index: Int) {
        constructor(type: KClass<*>, index: Int): this(type.java, index)
    }
}
