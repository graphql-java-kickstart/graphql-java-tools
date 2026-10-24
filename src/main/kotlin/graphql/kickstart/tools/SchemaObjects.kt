package graphql.kickstart.tools

import graphql.schema.*

/**
 * @author Andrew Potter
 */
data class SchemaObjects @JvmOverloads constructor(
    val query: GraphQLObjectType,
    val mutation: GraphQLObjectType?,
    val subscription: GraphQLObjectType?,
    val dictionary: Set<GraphQLNamedType>,
    val directives: Set<GraphQLDirective>,
    val codeRegistryBuilder: GraphQLCodeRegistry.Builder,
    val description: String?,
    // directives applied to the schema itself, e.g. `extend schema @link(...)`
    val schemaAppliedDirectives: List<GraphQLAppliedDirective> = emptyList()
) {
    /**
     * Makes a GraphQLSchema with query, mutation and subscription.
     */
    fun toSchema(): GraphQLSchema = newSchema()
        .mutation(mutation)
        .subscription(subscription)
        .build()

    /**
     * Makes a GraphQLSchema with query but without mutation and subscription.
     */
    fun toReadOnlySchema(): GraphQLSchema = newSchema().build()

    private fun newSchema(): GraphQLSchema.Builder = GraphQLSchema.newSchema()
        .description(description)
        .query(query)
        .additionalTypes(dictionary)
        .additionalDirectives(directives)
        .withSchemaAppliedDirectives(schemaAppliedDirectives)
        .codeRegistry(codeRegistryBuilder.build())
}
