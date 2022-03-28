package graphql.kickstart.tools

import graphql.schema.*

/**
 * @author Andrew Potter
 */
data class SchemaObjects(
    val query: GraphQLObjectType,
    val mutation: GraphQLObjectType?,
    val subscription: GraphQLObjectType?,
    val dictionary: Set<GraphQLType>,
    val directives: Set<GraphQLDirective>,
    val codeRegistryBuilder: GraphQLCodeRegistry.Builder,
    val description: String?
) {
    /**
     * Makes a GraphQLSchema with query, mutation and subscription.
     */
    fun toSchema(): GraphQLSchema {
        return GraphQLSchema.newSchema()
            .description(description)
            .query(query)
            .mutation(mutation)
            .subscription(subscription)
            .additionalTypes(dictionary)
            .additionalDirectives(directives)
            .codeRegistry(codeRegistryBuilder.build())
            .build()
    }

    /**
     * Makes a GraphQLSchema with query but without mutation and subscription.
     */
    fun toReadOnlySchema(): GraphQLSchema = GraphQLSchema.newSchema()
        .description(description)
        .query(query)
        .additionalTypes(dictionary)
        .build()
}
