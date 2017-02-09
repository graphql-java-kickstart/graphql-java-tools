package com.coxautodev.graphql.tools

import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType

/**
 * @author Andrew Potter
 */
data class SchemaObjects(val query: GraphQLObjectType, val mutation: GraphQLObjectType?, val dictionary: Set<GraphQLType>) {

    /**
     * Makes a GraphQLSchema with query and mutation.
     */
    fun toSchema(): GraphQLSchema = GraphQLSchema.newSchema()
        .query(query)
        .mutation(mutation)
        .build(dictionary)

    /**
     * Makes a GraphQLSchema with query but without mutation.
     */
    fun toReadOnlySchema(): GraphQLSchema = GraphQLSchema.newSchema()
        .query(query)
        .build(dictionary)
}