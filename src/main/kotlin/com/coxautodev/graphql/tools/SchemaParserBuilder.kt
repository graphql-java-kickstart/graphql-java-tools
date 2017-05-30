package com.coxautodev.graphql.tools

import com.google.common.collect.BiMap
import com.google.common.collect.HashBiMap
import com.google.common.collect.Maps
import graphql.parser.Parser
import graphql.schema.GraphQLScalarType

/**
 * @author Andrew Potter
 */
class SchemaParserBuilder constructor(private val dictionary: SchemaParserDictionary = SchemaParserDictionary()) {

    private val schemaString = StringBuilder()
    private val resolvers = mutableListOf<GraphQLResolver<*>>()
    private val scalars = mutableListOf<GraphQLScalarType>()

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
    fun dictionary(vararg dictionary: Class<*>) = this.apply {
        this.dictionary.add(*dictionary)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun dictionary(dictionary: List<Class<*>>) = this.apply {
        this.dictionary.add(dictionary)
    }

    /**
     * Build the parser with the supplied schema and dictionary.
     */
    fun build(): SchemaParser {
        val document = Parser().parseDocument(this.schemaString.toString())
        val definitions = document.definitions

        val resolvers = resolvers.map { Resolver(it) }
        val customScalars = scalars.associateBy { it.name }

        return SchemaClassScanner(dictionary.getDictionary(), definitions, resolvers, customScalars).scanForClasses()
    }
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
    fun add(vararg dictionary: Class<*>) = this.apply {
        dictionary.forEach { this.add(it) }
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun add(dictionary: List<Class<*>>) = this.apply {
        dictionary.forEach { this.add(it) }
    }
}

