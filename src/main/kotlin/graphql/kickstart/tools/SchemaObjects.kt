package graphql.kickstart.tools

import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.visibility.NoIntrospectionGraphqlFieldVisibility

/**
 * @author Andrew Potter
 */
data class SchemaObjects(val query: GraphQLObjectType, val mutation: GraphQLObjectType?, val subscription: GraphQLObjectType?, val dictionary: Set<GraphQLType>, val codeRegistryBuilder: GraphQLCodeRegistry.Builder) {

    /**
     * Makes a GraphQLSchema with query, mutation and subscription.
     */
    fun toSchema(introspectionEnabled: Boolean): GraphQLSchema {
        if (!introspectionEnabled) {
            codeRegistryBuilder.fieldVisibility(NoIntrospectionGraphqlFieldVisibility.NO_INTROSPECTION_FIELD_VISIBILITY)
        }

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
    @Suppress("unused")
    fun toReadOnlySchema(): GraphQLSchema = GraphQLSchema.newSchema()
            .query(query)
            .additionalTypes(dictionary)
            .build()
}
