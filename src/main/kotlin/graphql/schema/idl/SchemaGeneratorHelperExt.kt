package graphql.schema.idl

import graphql.introspection.Introspection
import graphql.language.Directive
import graphql.schema.GraphQLDirective
import graphql.schema.GraphqlTypeComparatorRegistry

class SchemaGeneratorHelperExt : SchemaGeneratorHelper() {
    // Re-expose a package protected method as public
    fun buildDirective(directive: Directive,
                       graphQLDirective: GraphQLDirective,
                       directiveLocation: Introspection.DirectiveLocation,
                       comparatorRegistry: GraphqlTypeComparatorRegistry): GraphQLDirective {
        // Note 1: for now, it seems safe to pass buildCtx = null, since the code path where buildCtx is used,
        //         is never called (directive.name == graphQLDirective.name is always true, see line with
        //         directiveDefOpt = FpKit.findOne ...)
        // Note 2: repeatable directives (new feature in graphql-java 16) likely don't work,
        //         since we don't pass in the full set of discovered directives
        return super.buildDirective(null, directive, setOf(graphQLDirective), directiveLocation, comparatorRegistry)
    }
}