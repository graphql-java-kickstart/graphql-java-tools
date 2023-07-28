package graphql.kickstart.tools

import graphql.language.Definition
import graphql.language.Document
import graphql.parser.MultiSourceReader
import graphql.parser.Parser
import graphql.parser.ParserEnvironment
import graphql.parser.ParserOptions
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaDirectiveWiring
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.misc.ParseCancellationException
import kotlin.Int.Companion.MAX_VALUE
import kotlin.reflect.KClass

/**
 * @author Andrew Potter
 */
class SchemaParserBuilder {

    private val dictionary = SchemaParserDictionary()
    private val schemaString = StringBuilder()
    private val files = mutableListOf<String>()
    private val resolvers = mutableListOf<GraphQLResolver<*>>()
    private val scalars = mutableListOf<GraphQLScalarType>()
    private val runtimeWiringBuilder = RuntimeWiring.newRuntimeWiring()
    private var options = SchemaParserOptions.defaultOptions()
    private val parser = Parser()
    private val parserOptions = ParserOptions
        .getDefaultParserOptions()
        .transform { o -> o.maxTokens(MAX_VALUE) }

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
        if (schemaString.isNotEmpty()) {
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

    fun directiveWiring(directive: SchemaDirectiveWiring) = this.apply {
        this.runtimeWiringBuilder.directiveWiring(directive)
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
        try {
            val documents = files.map { parseDocument(readFile(it), it) }.toMutableList()

            if (schemaString.isNotBlank()) {
                documents.add(parseDocument(schemaString.toString()))
            }

            return documents
        } catch (pce: ParseCancellationException) {
            val cause = pce.cause
            if (cause != null && cause is RecognitionException) {
                throw InvalidSchemaError(pce, cause)
            } else {
                throw pce
            }
        }
    }

    private fun parseDocument(input: String, sourceName: String? = null): Document {
        val sourceReader = MultiSourceReader
            .newMultiSourceReader()
            .string(input, sourceName)
            .trackData(true).build()
        val environment = ParserEnvironment
            .newParserEnvironment()
            .document(sourceReader)
            .parserOptions(parserOptions).build()
        return parser.parseDocument(environment)
    }

    private fun readFile(filename: String) =
        this::class.java.classLoader.getResource(filename)?.readText()
            ?: throw java.io.FileNotFoundException("classpath:$filename")

    /**
     * Build the parser with the supplied schema and dictionary.
     */
    fun build() = SchemaParser(scan(), options, runtimeWiringBuilder.build())
}

class InvalidSchemaError(
    pce: ParseCancellationException,
    private val recognitionException: RecognitionException
) : RuntimeException(pce) {
    override val message: String
        get() = "Invalid schema provided (${recognitionException.javaClass.name}) at: ${recognitionException.offendingToken}"
}
