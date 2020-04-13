package graphql.kickstart.tools

import graphql.parser.InvalidSyntaxException
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
        def e = thrown(InvalidSyntaxException)
        e.toString().contains(error)

        where:
        schema                      | error
        "invalid"                   | "offending token 'invalid' at line 1 column 1"
        "type Query {\ninvalid!\n}" | "offending token '!' at line 2 column 8"
    }
}
