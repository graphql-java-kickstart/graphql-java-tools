package graphql.schema.idl

import graphql.introspection.Introspection
import graphql.language.Directive
import graphql.schema.GraphQLDirective
import graphql.schema.GraphqlTypeComparatorRegistry
import graphql.schema.idl.RuntimeWiring.MOCKED_WIRING
import graphql.schema.idl.SchemaGenerator.Options.defaultOptions

class SchemaGeneratorHelperExt : SchemaGeneratorHelper() {
    // Re-expose a package protected method as public
    fun buildAppliedDirective(directive: Directive,
                              graphQLDirective: GraphQLDirective,
                              directiveLocation: Introspection.DirectiveLocation,
                              comparatorRegistry: GraphqlTypeComparatorRegistry): GraphQLDirective {
        // Note: repeatable directives (new feature in graphql-java 16) likely don't work,
        //         since we don't pass in the full set of discovered directives
        val context = BuildContext(TypeDefinitionRegistry(), MOCKED_WIRING, emptyMap(), defaultOptions())
        return super.buildAppliedDirective(context, directive, setOf(graphQLDirective), directiveLocation, comparatorRegistry)
    }
}
