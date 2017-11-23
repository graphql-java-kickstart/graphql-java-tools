package com.coxautodev.graphql.tools

import graphql.language.FieldDefinition
import graphql.language.TypeName
import spock.lang.Specification

/**
 * @author Andrew Potter
 */
class FieldResolverScannerSpec extends Specification {

    private static final SchemaParserOptions options = SchemaParserOptions.defaultOptions()
    private static final FieldResolverScanner scanner = new FieldResolverScanner(options)

    def "scanner finds fields on multiple root types"() {
        setup:
            def resolver = new RootResolverInfo([new RootQuery1(), new RootQuery2()], options)

        when:
            def result1 = scanner.findFieldResolver(new FieldDefinition("field1", new TypeName("String")), resolver)
            def result2 = scanner.findFieldResolver(new FieldDefinition("field2", new TypeName("String")), resolver)

        then:
            result1.search.source != result2.search.source
    }

    def "scanner throws exception when more than one resolver method is found"() {
        setup:
            def resolver = new RootResolverInfo([new RootQuery1(), new DuplicateQuery()], options)

        when:
            scanner.findFieldResolver(new FieldDefinition("field1", new TypeName("String")), resolver)

        then:
            thrown(FieldResolverError)
    }

    def "scanner throws exception when no resolver methods are found"() {
        setup:
            def resolver = new RootResolverInfo([], options)

        when:
            scanner.findFieldResolver(new FieldDefinition("field1", new TypeName("String")), resolver)

        then:
            thrown(FieldResolverError)
    }

    def "scanner finds properties when no method is found"() {
        setup:
            def resolver = new RootResolverInfo([new PropertyQuery()], options)

        when:
            def name = scanner.findFieldResolver(new FieldDefinition("name", new TypeName("String")), resolver)
            def version = scanner.findFieldResolver(new FieldDefinition("version", new TypeName("Integer")), resolver)

        then:
            name instanceof PropertyFieldResolver
            version instanceof PropertyFieldResolver
    }

    class RootQuery1 implements GraphQLQueryResolver {
        def field1() {}
    }

    class RootQuery2 implements GraphQLQueryResolver {
        def field2() {}
    }

    class DuplicateQuery implements GraphQLQueryResolver {
        def field1() {}
    }

    class ParentPropertyQuery {
        private Integer version = 1
    }

    class PropertyQuery extends ParentPropertyQuery implements GraphQLQueryResolver {
        private String name = "name"
    }
}
