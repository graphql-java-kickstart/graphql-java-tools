package com.coxautodev.graphql.tools

import com.google.common.collect.BiMap
import com.google.common.collect.HashBiMap
import graphql.parser.Parser
import graphql.schema.GraphQLScalarType

/**
 * @author Andrew Potter
 */
class SchemaParserBuilder(
    private val schemaString: StringBuilder = StringBuilder(),
    private val resolvers: MutableList<GraphQLResolver<*>> = mutableListOf(),
    private val dictionary: BiMap<String, Class<*>> = HashBiMap.create(),
    private val scalars: MutableList<GraphQLScalarType> = mutableListOf()) {

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
     * Add data classes to the parser's dictionary.
     */
    fun dataClasses(vararg dataClasses: Class<*>) = this.apply {
        this.dictionary(*dataClasses)
    }

    /**
     * Add enums to the parser's dictionary.
     */
    fun enums(vararg enums: Class<*>) = this.apply {
        this.dictionary(*enums)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun dictionary(name: String, clazz: Class<*>) = this.apply {
        this.dictionary.put(name, clazz)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun dictionary(dictionary: Map<String, Class<*>>) = this.apply {
        this.dictionary.putAll(dictionary)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun dictionary(clazz: Class<*>) = this.apply {
        this.dictionary(clazz.simpleName, clazz)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun dictionary(vararg dictionary: Class<*>) = this.apply {
        dictionary.forEach { this.dictionary(it) }
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun dictionary(dictionary: List<Class<*>>) = this.apply {
        dictionary.forEach { this.dictionary(it) }
    }

    /**
     * Add scalars to the parser's dictionary.
     */
    fun scalars(vararg scalars: GraphQLScalarType) = this.apply {
        this.scalars.addAll(scalars)
    }

    /**
     * Build the parser with the supplied schema and dictionary.
     */
    fun build(): SchemaParser {
        val document = Parser().parseDocument(this.schemaString.toString())
        val definitions = document.definitions

        val resolvers = resolvers.map { Resolver(it) }
        val customScalars = scalars.associateBy { it.name }

        return SchemaClassScanner(dictionary, definitions, resolvers, customScalars).scanForClasses()
    }
}

