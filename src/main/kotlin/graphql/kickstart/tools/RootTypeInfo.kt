package graphql.kickstart.tools

import graphql.language.Description
import graphql.language.SchemaDefinition
import graphql.language.TypeName

/**
 * @author Andrew Potter
 */
internal class RootTypeInfo private constructor(
    private val queryType: TypeName?,
    private val mutationType: TypeName?,
    private val subscriptionType: TypeName?,
    private val description: Description?
) {
    companion object {
        const val DEFAULT_QUERY_NAME = "Query"
        const val DEFAULT_MUTATION_NAME = "Mutation"
        const val DEFAULT_SUBSCRIPTION_NAME = "Subscription"
        const val DEFAULT_DESCRIPTION = "A GraphQL schema provides a root type for each kind of operation."

        fun fromSchemaDefinitions(definitions: List<SchemaDefinition>): RootTypeInfo {
            val queryType = definitions.lastOrNull()?.operationTypeDefinitions?.find { it.name == "query" }?.typeName
            val mutationType = definitions.lastOrNull()?.operationTypeDefinitions?.find { it.name == "mutation" }?.typeName
            val subscriptionType = definitions.lastOrNull()?.operationTypeDefinitions?.find { it.name == "subscription" }?.typeName
            val description = definitions.lastOrNull()?.description

            return RootTypeInfo(queryType, mutationType, subscriptionType, description)
        }
    }

    fun getQueryName() = queryType?.name ?: DEFAULT_QUERY_NAME
    fun getMutationName() = mutationType?.name ?: DEFAULT_MUTATION_NAME
    fun getSubscriptionName() = subscriptionType?.name ?: DEFAULT_SUBSCRIPTION_NAME
    fun getDescription() = description?.content ?: DEFAULT_DESCRIPTION

    fun isMutationRequired() = mutationType != null
    fun isSubscriptionRequired() = subscriptionType != null
}
