package graphql.schema.idl

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLUnionType

class DirectiveBehavior {

    private val directiveHelper = SchemaGeneratorDirectiveHelper()

    fun onObject(element: GraphQLObjectType, params: Params): GraphQLObjectType {
        return directiveHelper.onObject(element, params.toParameters())
    }

    fun onInterface(element: GraphQLInterfaceType, params: Params): GraphQLInterfaceType =
            directiveHelper.onInterface(element, params.toParameters())

    fun onUnion(element: GraphQLUnionType, params: Params): GraphQLUnionType =
            directiveHelper.onUnion(element, params.toParameters())

    fun onScalar(element: GraphQLScalarType, params: Params): GraphQLScalarType =
            directiveHelper.onScalar(element, params.toParameters())

    fun onEnum(element: GraphQLEnumType, params: Params): GraphQLEnumType =
            directiveHelper.onEnum(element, params.toParameters())

    fun onInputObject(element: GraphQLInputObjectType, params: Params): GraphQLInputObjectType =
            directiveHelper.onInputObjectType(element, params.toParameters())

    data class Params(val runtimeWiring: RuntimeWiring) {
        internal fun toParameters() = SchemaGeneratorDirectiveHelper.Parameters(null, runtimeWiring, null, null)
    }

}
