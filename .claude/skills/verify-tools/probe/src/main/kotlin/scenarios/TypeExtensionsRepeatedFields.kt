package scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema

object TypeExtensionsRepeatedFields : Scenario {
    override val name = "type-extensions-repeated-fields"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/type-extensions-repeated-fields/schema.graphqls")
        .resolvers(TypeExtensionsRepeatedFieldsQuery())

    override fun inspect(schema: GraphQLSchema): String {
        val objects = listOf("TypeAndExtension", "TwoExtensions", "OneDefinition").map {
            "$it.name: ${(schema.getType(it) as GraphQLObjectType).getFieldDefinition("name").description}"
        }
        val inputs = listOf("InputTypeAndExtension", "InputTwoExtensions", "InputOneDefinition").map {
            "$it.id: ${(schema.getType(it) as GraphQLInputObjectType).getField("id").description}"
        }
        return (objects + inputs).joinToString("\n")
    }
}

data class TypeExtensionsRepeatedFieldsTypeAndExtension(val name: String?)

data class TypeExtensionsRepeatedFieldsTwoExtensions(val id: String?, val name: String?)

data class TypeExtensionsRepeatedFieldsOneDefinition(val name: String?)

data class TypeExtensionsRepeatedFieldsInputTypeAndExtension(val id: String?)

data class TypeExtensionsRepeatedFieldsInputTwoExtensions(val term: String?, val id: String?)

data class TypeExtensionsRepeatedFieldsInputOneDefinition(val id: String?)

class TypeExtensionsRepeatedFieldsQuery : GraphQLQueryResolver {
    fun typeAndExtension() = TypeExtensionsRepeatedFieldsTypeAndExtension("a")

    fun twoExtensions() = TypeExtensionsRepeatedFieldsTwoExtensions("b", "c")

    fun oneDefinition() = TypeExtensionsRepeatedFieldsOneDefinition("d")

    fun echo(
        typeAndExtension: TypeExtensionsRepeatedFieldsInputTypeAndExtension?,
        twoExtensions: TypeExtensionsRepeatedFieldsInputTwoExtensions?,
        oneDefinition: TypeExtensionsRepeatedFieldsInputOneDefinition?
    ): String = listOf(typeAndExtension?.id, twoExtensions?.term, twoExtensions?.id, oneDefinition?.id).joinToString(",")
}
