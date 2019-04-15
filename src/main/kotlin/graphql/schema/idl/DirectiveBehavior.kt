package graphql.schema.idl

import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
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

    fun onField(element: GraphQLFieldDefinition, params: Params): GraphQLFieldDefinition {
        // noop, since the actual behaviour has moved to onObject/onInterface since graphql-java 12.0
        return element
    }

    fun onInterface(element: GraphQLInterfaceType, params: Params): GraphQLInterfaceType =
            directiveHelper.onInterface(element, params.toParameters())

    fun onUnion(element: GraphQLUnionType, params: Params): GraphQLUnionType =
            directiveHelper.onUnion(element, params.toParameters())

    fun onScalar(element: GraphQLScalarType, params: Params): GraphQLScalarType =
            directiveHelper.onScalar(element, params.toParameters())

    fun onEnum(element: GraphQLEnumType, params: Params): GraphQLEnumType =
            directiveHelper.onEnum(element, params.toParameters())

    fun onEnumValue(element: GraphQLEnumValueDefinition, params: Params): GraphQLEnumValueDefinition {
        // noop, since the actual behaviour has moved to onEnum since graphql-java 12.0
        return element
    }

    fun onArgument(element: GraphQLArgument, params: Params): GraphQLArgument {
        // noop, since the actual behaviour has moved to onObject/onInterface since graphql-java 12.0
        return element
    }

    fun onInputObject(element: GraphQLInputObjectType, params: Params): GraphQLInputObjectType =
            directiveHelper.onInputObjectType(element, params.toParameters())

    fun onInputObjectField(element: GraphQLInputObjectField, params: Params): GraphQLInputObjectField {
        // noop, since the actual behaviour has moved to onInputObjectType since graphql-java 12.0
        return element
    }

    data class Params(val runtimeWiring: RuntimeWiring) {
        internal fun toParameters() = SchemaGeneratorDirectiveHelper.Parameters(null, runtimeWiring, null, null)
    }

}
