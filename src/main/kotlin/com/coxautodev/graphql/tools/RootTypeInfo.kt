package com.coxautodev.graphql.tools

import graphql.language.SchemaDefinition
import graphql.language.TypeName

/**
 * @author Andrew Potter
 */
class RootTypeInfo private constructor(val queryType: TypeName?, val mutationType: TypeName?) {
    companion object {
        fun fromSchemaDefinitions(definitions: List<SchemaDefinition>): RootTypeInfo {
            val queryType = definitions.lastOrNull()?.operationTypeDefinitions?.find { it.name == "query" }?.type as TypeName?
            val mutationType = definitions.lastOrNull()?.operationTypeDefinitions?.find { it.name == "mutation" }?.type as TypeName?

            return RootTypeInfo(queryType, mutationType)
        }
    }

    fun getQueryName() = queryType?.name ?: "Query"
    fun getMutationName() = mutationType?.name ?: "Mutation"

    fun isMutationRequired() = mutationType != null
}