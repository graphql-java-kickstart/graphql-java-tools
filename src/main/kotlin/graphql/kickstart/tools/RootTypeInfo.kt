package graphql.kickstart.tools

import graphql.language.SchemaDefinition
import graphql.language.TypeName
import graphql.schema.idl.TypeDefinitionRegistry

/**
 * @author Andrew Potter
 */
internal class RootTypeInfo private constructor(
    private val queryType: TypeName?,
    private val mutationType: TypeName?,
    private val subscriptionType: TypeName?
) {
    companion object {
        const val DEFAULT_QUERY_NAME = "Query"
        const val DEFAULT_MUTATION_NAME = "Mutation"
        const val DEFAULT_SUBSCRIPTION_NAME = "Subscription"

        fun fromSchemaDefinitions(typeDefinitionRegistry: TypeDefinitionRegistry): RootTypeInfo {
            val schemaDefinition: SchemaDefinition? = typeDefinitionRegistry.schemaDefinition().orElse(null)
            val queryType = schemaDefinition?.operationTypeDefinitions?.find { it.name == "query" }?.typeName
            val mutationType = schemaDefinition?.operationTypeDefinitions?.find { it.name == "mutation" }?.typeName
            val subscriptionType = schemaDefinition?.operationTypeDefinitions?.find { it.name == "subscription" }?.typeName

            return RootTypeInfo(queryType, mutationType, subscriptionType)
        }
    }

    fun getQueryName() = queryType?.name ?: DEFAULT_QUERY_NAME
    fun getMutationName() = mutationType?.name ?: DEFAULT_MUTATION_NAME
    fun getSubscriptionName() = subscriptionType?.name ?: DEFAULT_SUBSCRIPTION_NAME

    fun isMutationRequired() = mutationType != null
    fun isSubscriptionRequired() = subscriptionType != null
}
