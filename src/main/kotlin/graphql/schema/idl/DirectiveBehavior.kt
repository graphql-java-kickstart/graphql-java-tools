package graphql.schema.idl

import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType

class DirectiveBehavior {

    private val directiveHelper = SchemaGeneratorDirectiveHelper()

    fun onObject(element: GraphQLObjectType, params: Params): GraphQLObjectType {
        return directiveHelper.onObject(element, params.toParameters())
    }

    fun onField(element: GraphQLFieldDefinition, params: Params): GraphQLFieldDefinition {
        return directiveHelper.onField(element, params.toParameters())
    }


    data class Params(val runtimeWiring: RuntimeWiring) {
        internal fun toParameters() = SchemaGeneratorDirectiveHelper.Parameters(null, runtimeWiring, null, null)
    }
}
