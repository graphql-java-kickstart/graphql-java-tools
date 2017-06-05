package com.coxautodev.graphql.tools

import graphql.language.SchemaDefinition
import graphql.language.TypeName

/**
 * @author Andrew Potter
 */
class RootTypeInfo private constructor(val queryType: TypeName?, val mutationType: TypeName?) {
    companion object {
        const val DEFAULT_QUERY_NAME = "Query"
        const val DEFAULT_MUTATION_NAME = "Mutation"

        fun fromSchemaDefinitions(definitions: List<SchemaDefinition>): RootTypeInfo {
            val queryType = definitions.lastOrNull()?.operationTypeDefinitions?.find { it.name == "query" }?.type as TypeName?
            val mutationType = definitions.lastOrNull()?.operationTypeDefinitions?.find { it.name == "mutation" }?.type as TypeName?

            return RootTypeInfo(queryType, mutationType)
        }
    }

    fun getQueryName() = queryType?.name ?: DEFAULT_QUERY_NAME
    fun getMutationName() = mutationType?.name ?: DEFAULT_MUTATION_NAME

    fun isMutationRequired() = mutationType != null
}