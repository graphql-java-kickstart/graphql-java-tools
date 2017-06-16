package com.coxautodev.graphql.tools

import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType

/**
 * @author Andrew Potter
 */
data class SchemaObjects(val query: GraphQLObjectType, val mutation: GraphQLObjectType?, val subscription: GraphQLObjectType?, val dictionary: Set<GraphQLType>) {

    /**
     * Makes a GraphQLSchema with query, mutation and subscription.
     */
    fun toSchema(): GraphQLSchema = GraphQLSchema.newSchema()
        .query(query)
        .mutation(mutation)
        .subscription(subscription)
        .build(dictionary)

    /**
     * Makes a GraphQLSchema with query but without mutation and subscription.
     */
    fun toReadOnlySchema(): GraphQLSchema = GraphQLSchema.newSchema()
        .query(query)
        .build(dictionary)
}