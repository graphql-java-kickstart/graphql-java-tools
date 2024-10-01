package graphql.kickstart.tools

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.ExecutionInput
import graphql.GraphQL

private val mapper = ObjectMapper()

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

    return result.getData() as Map<String, Any>
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
