package graphql.kickstart.tools.compat.scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLSchema

object TypeExtensionsInputOrder : Scenario {
    override val name = "type-extensions-input-order"

    override val files = listOf("compat/scenarios/type-extensions-input-order/schema.graphqls")
    override val resolvers = listOf(TypeExtensionsInputOrderQuery())

    override fun inspect(schema: GraphQLSchema) = "Filter fields: ${(schema.getType("Filter") as GraphQLInputObjectType).fieldDefinitions.map { it.name }}"
}

data class TypeExtensionsInputOrderFilter(val term: String?, val limit: Int?, val offset: Int?)

class TypeExtensionsInputOrderQuery : GraphQLQueryResolver {
    fun count(filter: TypeExtensionsInputOrderFilter?): Int = listOfNotNull(filter?.term, filter?.limit, filter?.offset).size
}
