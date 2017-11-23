package com.coxautodev.graphql.tools

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.ExecutionInput
import graphql.GraphQL
import groovy.transform.CompileStatic

/**
 * @author Andrew Potter
 */
@CompileStatic
class Utils {
    private static ObjectMapper mapper = new ObjectMapper()

    static Map<String, Object> assertNoGraphQlErrors(GraphQL gql, Map<String, Object> args = [:], Object context = new Object(), Closure<String> closure) {
        def result = gql.execute(ExecutionInput.newExecutionInput()
            .query(closure())
            .context(context)
            .root(context)
            .variables(args))

        if(!result.errors.isEmpty()) {
            throw new AssertionError("GraphQL result contained errors!\n${result.errors.collect { mapper.writeValueAsString(it) }.join("\n")}")
        }

        return result.data as Map<String, Object>
    }
}
