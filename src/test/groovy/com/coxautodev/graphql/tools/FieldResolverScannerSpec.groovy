package com.coxautodev.graphql.tools

import graphql.language.FieldDefinition
import graphql.language.TypeName
import spock.lang.Specification

/**
 * @author Andrew Potter
 */
class FieldResolverScannerSpec extends Specification {

    private static final FieldResolverScanner scanner = new FieldResolverScanner()

    def "field resolver finds fields on multiple root types"() {
        setup:
            def resolver = new RootResolverInfo([new RootQuery1(), new RootQuery2()])

        when:
            def result1 = scanner.findFieldResolver(new FieldDefinition("field1", new TypeName("String")), resolver)
            def result2 = scanner.findFieldResolver(new FieldDefinition("field2", new TypeName("String")), resolver)

        then:
            result1.search.source != result2.search.source
    }

    class RootQuery1 implements GraphQLQueryResolver {
        def field1() {}
    }

    class RootQuery2 implements GraphQLQueryResolver {
        def field2() {}
    }
}
