package com.coxautodev.graphql.tools

import graphql.language.SchemaDefinition
import graphql.language.TypeName

/**
 * @author Andrew Potter
 */
class RootTypeInfo private constructor(val queryType: TypeName?, val mutationType: TypeName?) {
    companion object {
        @JvmStatic val defaultQueryName = "Query"
        @JvmStatic val defaultMutationName = "Mutation"

        fun fromSchemaDefinitions(definitions: List<SchemaDefinition>): RootTypeInfo {
            val queryType = definitions.lastOrNull()?.operationTypeDefinitions?.find { it.name == "query" }?.type as TypeName?
            val mutationType = definitions.lastOrNull()?.operationTypeDefinitions?.find { it.name == "mutation" }?.type as TypeName?

            return RootTypeInfo(queryType, mutationType)
        }
    }

    fun getQueryName() = queryType?.name ?: defaultQueryName
    fun getMutationName() = mutationType?.name ?: defaultMutationName

    fun isMutationRequired() = mutationType != null
}