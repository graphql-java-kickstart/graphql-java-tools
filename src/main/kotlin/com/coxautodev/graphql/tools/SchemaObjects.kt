package com.coxautodev.graphql.tools

import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.visibility.NoIntrospectionGraphqlFieldVisibility

/**
 * @author Andrew Potter
 */
data class SchemaObjects(val query: GraphQLObjectType, val mutation: GraphQLObjectType?, val subscription: GraphQLObjectType?, val dictionary: Set<GraphQLType>) {

    /**
     * Makes a GraphQLSchema with query, mutation and subscription.
     */
    fun toSchema(introspectionEnabled: Boolean): GraphQLSchema {
        val builder = GraphQLSchema.newSchema()
                .query(query)
                .mutation(mutation)
                .subscription(subscription)
                .additionalTypes(dictionary)

        if (!introspectionEnabled) {
            builder.fieldVisibility(NoIntrospectionGraphqlFieldVisibility.NO_INTROSPECTION_FIELD_VISIBILITY)
        }

        return builder.build()
    }

    /**
     * Makes a GraphQLSchema with query but without mutation and subscription.
     */
    fun toReadOnlySchema(): GraphQLSchema = GraphQLSchema.newSchema()
            .query(query)
            .additionalTypes(dictionary)
            .build()
}
