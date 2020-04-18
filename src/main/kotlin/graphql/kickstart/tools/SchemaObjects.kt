package graphql.kickstart.tools

import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType

/**
 * @author Andrew Potter
 */
data class SchemaObjects(
    val query: GraphQLObjectType,
    val mutation: GraphQLObjectType?,
    val subscription: GraphQLObjectType?,
    val dictionary: Set<GraphQLType>,
    val codeRegistryBuilder: GraphQLCodeRegistry.Builder
) {
    /**
     * Makes a GraphQLSchema with query, mutation and subscription.
     */
    fun toSchema(): GraphQLSchema {
        return GraphQLSchema.newSchema()
            .query(query)
            .mutation(mutation)
            .subscription(subscription)
            .additionalTypes(dictionary)
            .codeRegistry(codeRegistryBuilder.build())
            .build()
    }

    /**
     * Makes a GraphQLSchema with query but without mutation and subscription.
     */
    fun toReadOnlySchema(): GraphQLSchema = GraphQLSchema.newSchema()
        .query(query)
        .additionalTypes(dictionary)
        .build()
}
