package scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLSchema

/** An input object whose extensions come before and after the type in the SDL. inspect.txt lists the fields in schema order. */
object TypeExtensionsInputOrder : Scenario {
    override val name = "type-extensions-input-order"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/type-extensions-input-order/schema.graphqls")
        .resolvers(TypeExtensionsInputOrderQuery())

    override fun inspect(schema: GraphQLSchema) = "Filter fields: ${(schema.getType("Filter") as GraphQLInputObjectType).fieldDefinitions.map { it.name }}"
}

data class TypeExtensionsInputOrderFilter(val term: String?, val limit: Int?, val offset: Int?)

class TypeExtensionsInputOrderQuery : GraphQLQueryResolver {
    fun count(filter: TypeExtensionsInputOrderFilter?): Int = listOfNotNull(filter?.term, filter?.limit, filter?.offset).size
}
