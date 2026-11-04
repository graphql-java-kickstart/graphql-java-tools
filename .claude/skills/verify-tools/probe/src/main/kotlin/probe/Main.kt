package probe

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.kickstart.tools.SchemaParser
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaPrinter
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import scenarios.SCENARIOS
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Builds the scenario's schema through the public API of the graphql-java-tools jar on the classpath, then runs every
 * `.graphql` file under `scenarios/<name>/` against it. Everything it observes goes to the output directory.
 *
 * Exit codes: 0 schema built and all operations ran (operations may still have GraphQL errors, see summary.txt),
 * 2 schema build threw, 64 bad arguments.
 */
fun main(args: Array<String>) {
    if (args.size != 2) usage("expected <scenario> <outDir>")
    val scenario = SCENARIOS.find { it.name == args[0] } ?: usage("unknown scenario '${args[0]}'")
    val out = File(args[1]).apply { mkdirs() }
    val summary = StringBuilder()
    fun report(line: String) {
        println(line)
        summary.appendLine(line)
    }

    report("scenario: ${scenario.name}")
    report("graphql-java-tools jar: ${jarOf(SchemaParser::class.java)}")
    report("graphql-java jar: ${jarOf(GraphQL::class.java)}")
    report("java: ${System.getProperty("java.version")}")

    val schema: GraphQLSchema
    try {
        val parser = scenario.parser().build()
        schema = scenario.schema(parser)
        val unused = parser.getUnusedDefinitions().map { it.name }.sorted()
        if (unused.isNotEmpty()) report("unused definitions: ${unused.joinToString()}")
    } catch (e: Throwable) {
        File(out, "build-error.txt").writeText(e.stackTraceToString())
        report("schema build: FAILED ${e.javaClass.name}: ${e.message}")
        File(out, "summary.txt").writeText(summary.toString())
        exitProcess(2)
    }
    File(out, "schema.graphqls").writeText(SchemaPrinter().print(schema))
    scenario.inspect(schema)?.let { File(out, "inspect.txt").writeText(it) }
    report("schema build: OK")

    val graphQL = scenario.graphQL(GraphQL.newGraphQL(schema)).build()
    for (operation in operationFiles(scenario.operations)) {
        val name = operation.nameWithoutExtension
        val variablesFile = File(operation.parentFile, "$name.variables.json")
        @Suppress("UNCHECKED_CAST")
        val variables = if (variablesFile.exists()) json.readValue(variablesFile, Map::class.java) as Map<String, Any?> else emptyMap()
        val input = scenario.input(ExecutionInput.newExecutionInput().query(operation.readText()).variables(variables)).build()

        val result = try {
            graphQL.execute(input)
        } catch (e: Throwable) {
            File(out, "$name.json").writeText(json.writeValueAsString(mapOf("threw" to e.toString())))
            report("operation $name: THREW $e")
            continue
        }
        // A subscription's data is a Publisher of results
        @Suppress("UNCHECKED_CAST")
        val publisher = result.getData<Any>() as? Publisher<ExecutionResult>
        val written = LinkedHashMap<String, Any?>()
        var eventCount = ""
        if (publisher != null) {
            val events = collect(publisher)
            written["events"] = events
            result.toSpecification()["errors"]?.let { written["errors"] = it }
            eventCount = " events=${events.size}"
        } else {
            written.putAll(result.toSpecification())
        }
        File(out, "$name.json").writeText(json.writeValueAsString(written))
        report("operation $name: errors=${result.errors.size}$eventCount")
        result.errors.forEach { report("  error: ${it.message}") }
    }
    File(out, "summary.txt").writeText(summary.toString())
}

private val json = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

private fun usage(problem: String): Nothing {
    System.err.println("$problem\nusage: probe <scenario> <outDir>\nscenarios: ${SCENARIOS.joinToString { it.name }}")
    exitProcess(64)
}

private fun jarOf(clazz: Class<*>) = clazz.protectionDomain.codeSource?.location?.path ?: "unknown"

private fun operationFiles(scenario: String): List<File> {
    val dir = Thread.currentThread().contextClassLoader.getResource("scenarios/$scenario")
        ?: return emptyList()
    return File(dir.toURI()).listFiles { file -> file.extension == "graphql" }!!.sortedBy { it.name }
}

/** Subscribes the way a transport would and collects every event until completion, for up to 10 seconds. */
private fun collect(publisher: Publisher<ExecutionResult>): List<Map<String, Any?>> {
    val events = mutableListOf<Map<String, Any?>>()
    val done = CountDownLatch(1)
    publisher.subscribe(object : Subscriber<ExecutionResult> {
        override fun onSubscribe(subscription: Subscription) = subscription.request(Long.MAX_VALUE)
        override fun onNext(event: ExecutionResult) {
            synchronized(events) { events.add(event.toSpecification()) }
        }
        override fun onError(error: Throwable) {
            synchronized(events) { events.add(mapOf("publisherError" to error.toString())) }
            done.countDown()
        }
        override fun onComplete() = done.countDown()
    })
    if (!done.await(10, TimeUnit.SECONDS)) println("  subscription did not complete within 10 seconds")
    return synchronized(events) { events.toList() }
}
