package graphql.kickstart.tools

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import graphql.ExecutionInput
import graphql.GraphQL
import org.slf4j.LoggerFactory

fun assertNoGraphQlErrors(gql: GraphQL, args: Map<String, Any> = mapOf(), context: Map<Any, Any> = mapOf(), closure: () -> String): Map<String, Any> {
    val result = gql.execute(
        ExecutionInput.newExecutionInput()
            .query(closure.invoke())
            .graphQLContext(context)
            .root(context)
            .variables(args)
    )

    if (result.errors.isNotEmpty()) {
        throw AssertionError("GraphQL result contained errors!\n${result.errors.map { it.message }.joinToString("\n")}")
    }

    return checkNotNull(result.getData<Map<String, Any>>())
}

fun <T> assertEquals(actual: T, expected: T) {
    assert(actual == expected) { "expected:<$expected> but was:<$actual>" }
}

fun <T> assertNotEquals(actual: T, unexpected: T) {
    assert(actual != unexpected) { "Values should be different. Actual: $actual" }
}

fun <T> assertNull(actual: T) {
    assertEquals(actual, null)
}

fun <T> assertNotNull(actual: T) {
    assert(actual != null)
}

fun <T> withWarnings(block: () -> T): Pair<T, List<String>> {
    // the tests run in the library's module, which doesn't read logback's
    listOf("ch.qos.logback.classic", "ch.qos.logback.core").forEach { name ->
        ModuleLayer.boot().findModule(name).ifPresent { SchemaParser::class.java.module.addReads(it) }
    }
    val logger = LoggerFactory.getLogger("graphql.kickstart.tools") as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    try {
        val result = block()
        return result to appender.list.filter { it.level.isGreaterOrEqual(Level.WARN) }.map { it.formattedMessage }
    } finally {
        logger.detachAppender(appender)
    }
}
