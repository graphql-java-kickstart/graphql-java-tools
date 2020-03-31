package graphql.kickstart.tools

import graphql.kickstart.tools.directive.SchemaGeneratorDirectiveHelper
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring

/**
 * Directive behavior is used to wire up directives during schema parsing. Unfortunately, SchemaGeneratorDirectiveHelper
 * which contains the logic has package-private access to some members and must be therefore accessed via reflection.
 */
class DirectiveBehavior {

  private val directiveHelper = SchemaGeneratorDirectiveHelper()

  fun onObject(element: GraphQLObjectType, params: Params): GraphQLObjectType =
      directiveHelper.onObject(element, params.toParameters()) as GraphQLObjectType

  fun onInterface(element: GraphQLInterfaceType, params: Params): GraphQLInterfaceType =
      directiveHelper.onInterface(element, params.toParameters()) as GraphQLInterfaceType

  fun onUnion(element: GraphQLUnionType, params: Params): GraphQLUnionType =
      directiveHelper.onUnion(element, params.toParameters()) as GraphQLUnionType

  fun onScalar(element: GraphQLScalarType, params: Params): GraphQLScalarType =
      directiveHelper.onScalar(element, params.toParameters()) as GraphQLScalarType

  fun onEnum(element: GraphQLEnumType, params: Params): GraphQLEnumType =
      directiveHelper.onEnum(element, params.toParameters()) as GraphQLEnumType

  fun onInputObject(element: GraphQLInputObjectType, params: Params): GraphQLInputObjectType =
      directiveHelper.onInputObjectType(element, params.toParameters()) as GraphQLInputObjectType

  data class Params(val runtimeWiring: RuntimeWiring, val codeRegistryBuilder: GraphQLCodeRegistry.Builder) {
    internal fun toParameters() = SchemaGeneratorDirectiveHelper.Parameters(null, runtimeWiring, null, codeRegistryBuilder)
  }

}
