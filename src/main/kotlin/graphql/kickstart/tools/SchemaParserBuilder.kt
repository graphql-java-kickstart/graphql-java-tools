package graphql.kickstart.tools

import graphql.language.Definition
import graphql.language.Document
import graphql.language.SourceLocation
import graphql.parser.InvalidSyntaxException
import graphql.parser.MultiSourceReader
import graphql.parser.Parser
import graphql.parser.ParserEnvironment
import graphql.parser.ParserOptions
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaDirectiveWiring
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.misc.ParseCancellationException
import java.text.MessageFormat
import kotlin.Int.Companion.MAX_VALUE
import kotlin.reflect.KClass

/**
 * @author Andrew Potter
 */
class SchemaParserBuilder {

    private val dictionary = SchemaParserDictionary()
    private val schemaStrings = mutableListOf<Pair<String, String?>>()
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
        schemaStrings.add(string to null)
    }

    /**
     * Add a GraphQL schema string directly, naming its source in parse errors and source locations.
     */
    fun schemaString(string: String, sourceName: String) = this.apply {
        schemaStrings.add(string to sourceName)
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
            val documents = files.map { parseDocument(listOf(readFile(it) to it)) }.toMutableList()

            if (schemaStrings.any { it.first.isNotBlank() }) {
                documents.add(parseDocument(schemaStrings))
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

    private fun parseDocument(sources: List<Pair<String, String?>>): Document {
        // MultiSourceReader numbers the last line of the last source from the start of the first one. Ending
        // every source with a line break leaves only the end of input there, whose line is fixed below.
        val inputs = sources.map { (input, _) -> if (sources.size > 1 && !input.endsWith("\n")) "$input\n" else input }
        val sourceReaderBuilder = MultiSourceReader.newMultiSourceReader()
        inputs.forEachIndexed { index, input -> sourceReaderBuilder.string(input, sources[index].second) }
        val sourceReader = sourceReaderBuilder.trackData(true).build()
        val environment = ParserEnvironment
            .newParserEnvironment()
            .document(sourceReader)
            .parserOptions(parserOptions).build()
        try {
            return parser.parseDocument(environment)
        } catch (e: InvalidSyntaxException) {
            val location = e.location ?: throw e
            val linesBeforeLast = inputs.dropLast(1).sumOf { it.lines().size - 1 }
            val endOfInput = location.line == linesBeforeLast + inputs.last().lines().size
            val line = if (endOfInput) location.line - linesBeforeLast else location.line
            if (line == location.line && location.sourceName == null) throw e

            val message = e.message.orEmpty().replaceFirst(" ${formatLine(location.line)} ", " ${formatLine(line)} ") +
                location.sourceName?.let { " in $it" }.orEmpty()
            throw SchemaSyntaxException(message, SourceLocation(line, location.column, location.sourceName), e)
        }
    }

    // Same formatting graphql-java uses for the line in its messages, e.g. "1,103" in English.
    private fun formatLine(line: Int) = MessageFormat("{0}").format(arrayOf<Any>(line))

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

internal class SchemaSyntaxException(message: String, location: SourceLocation, e: InvalidSyntaxException) :
    InvalidSyntaxException(message, location, e.offendingToken, e.sourcePreview, e)
