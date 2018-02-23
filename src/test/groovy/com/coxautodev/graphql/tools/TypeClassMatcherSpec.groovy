package com.coxautodev.graphql.tools

import graphql.language.FieldDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.TypeDefinition
import graphql.language.TypeName
import spock.lang.Specification
import spock.lang.Unroll

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/**
 * @author Andrew Potter
 */
class TypeClassMatcherSpec extends Specification {

    private static final graphql.language.Type customType = new TypeName("CustomType")
    private static final TypeDefinition customDefinition = new ObjectTypeDefinition("CustomType")

    private static final TypeClassMatcher matcher = new TypeClassMatcher([CustomType: customDefinition])
    private static final SchemaParserOptions options = SchemaParserOptions.defaultOptions()
    private static final FieldResolverScanner scanner = new FieldResolverScanner(options)

    private final resolver = new RootResolverInfo([new QueryMethods()], options)

    private TypeClassMatcher.PotentialMatch createPotentialMatch(String methodName, graphql.language.Type graphQLType) {
        scanner.findFieldResolver(new FieldDefinition(methodName, graphQLType), resolver)
            .scanForMatches()
            .find { it.location == TypeClassMatcher.Location.RETURN_TYPE }
    }

    private graphql.language.Type list(graphql.language.Type other = customType) {
        new ListType(other)
    }

    private graphql.language.Type nonNull(graphql.language.Type other = customType) { new NonNullType(other)
    }

    @Unroll
    def "matcher verifies that nested return type matches graphql definition for method #methodName"() {
        when:
            def match = matcher.match(createPotentialMatch(methodName, type))

        then:
            noExceptionThrown()
            match.type == customDefinition
            match.clazz == CustomType

        where:
            methodName            | type
            "type"                | customType
            "futureType"          | customType
            "listType"            | list()
            "listListType"        | list(list())
            "futureListType"      | list()
            "listFutureType"      | list()
            "listListFutureType"  | list(list())
            "futureListListType"  | list(list())
            "superType"           | customType
            "superListFutureType" | list()
            "nullableType"        | customType
            "nullableListType"    | list(nonNull(customType))
    }

    @Unroll
    def "matcher verifies that nested return type doesn't match graphql definition for method #methodName"() {
        when:
            matcher.match(createPotentialMatch(methodName, type))

        then:
            thrown(SchemaClassScannerError)

        where:
            methodName     | type
            "type"         | list()
            "futureType"   | list()
    }

    def "matcher does not allow parameterized types as root types"() {
        when:
            matcher.match(createPotentialMatch("genericCustomType", customType))

        then:
            thrown(TypeClassMatcher.RawClassRequiredForGraphQLMappingException)
    }

    private class Super<Unused, Type, ListFutureType> implements GraphQLQueryResolver {
        Type superType() { null }
        ListFutureType superListFutureType() { null }
    }

    private class QueryMethods extends Super<Void, CustomType, List<CompletableFuture<CustomType>>> {
        CustomType type() { null }
        Future<CustomType> futureType() { null }
        List<CustomType> listType() { null }
        List<List<CustomType>> listListType() { null }
        CompletableFuture<List<CustomType>> futureListType() { null }
        List<CompletableFuture<CustomType>> listFutureType() { null }
        List<List<CompletableFuture<CustomType>>> listListFutureType() { null }
        CompletableFuture<List<List<CustomType>>> futureListListType() { null }

        Optional<CustomType> nullableType() { null }
        Optional<List<CustomType>> nullableListType() { null }
        Optional<Optional<CustomType>> nullableNullableType() { null }
        List<Optional<CustomType>> listNullableType() { null }

        GenericCustomType<String> genericCustomType() { null }
    }

    private class CustomType {}
    private class GenericCustomType<T> {}
}
