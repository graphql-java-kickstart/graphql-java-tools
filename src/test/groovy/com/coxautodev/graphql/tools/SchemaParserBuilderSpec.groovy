package com.coxautodev.graphql.tools

import spock.lang.Specification
import spock.lang.Unroll

/**
 * @author Andrew Potter
 */
class SchemaParserBuilderSpec extends Specification {

    @Unroll
    def "parser errors should be returned in full"() {
        when:
            SchemaParser.newParser()
                .schemaString(schema)
                .build()

        then:
            def e = thrown(InvalidSchemaError)
            e.toString().contains(error)

        where:
            schema | error
            "invalid"                   | "0,0:6='invalid'"
            "type Query {\ninvalid!\n}" | "4,20:20='!'"
    }
}
