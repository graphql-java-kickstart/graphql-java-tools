package graphql.kickstart.tools

import graphql.language.Description
import graphql.language.Directive
import graphql.language.SchemaDefinition
import graphql.language.SchemaExtensionDefinition
import graphql.language.TypeName

/**
 * @author Andrew Potter
 */
internal class RootTypeInfo private constructor(
    private val queryType: TypeName?,
    private val mutationType: TypeName?,
    private val subscriptionType: TypeName?,
    private val description: Description?,
    private val directives: List<Directive>
) {
    companion object {
        const val DEFAULT_QUERY_NAME = "Query"
        const val DEFAULT_MUTATION_NAME = "Mutation"
        const val DEFAULT_SUBSCRIPTION_NAME = "Subscription"

        fun fromSchemaDefinitions(definitions: List<SchemaDefinition>): RootTypeInfo {
            // SchemaExtensionDefinition is a subclass of SchemaDefinition, so `definitions` contains the extensions too
            val schemaDefinition = definitions.lastOrNull { it !is SchemaExtensionDefinition }
            val extensionDefinitions = definitions.filterIsInstance<SchemaExtensionDefinition>()
            // the schema definition comes first, then its extensions
            val allDefinitions = listOfNotNull(schemaDefinition) + extensionDefinitions

            val operationTypes = allDefinitions.flatMap { it.operationTypeDefinitions }.associate { it.name to it.typeName }

            return RootTypeInfo(
                operationTypes["query"],
                operationTypes["mutation"],
                operationTypes["subscription"],
                schemaDefinition?.description,
                allDefinitions.flatMap { it.directives }
            )
        }
    }

    fun getQueryName() = queryType?.name ?: DEFAULT_QUERY_NAME
    fun getMutationName() = mutationType?.name ?: DEFAULT_MUTATION_NAME
    fun getSubscriptionName() = subscriptionType?.name ?: DEFAULT_SUBSCRIPTION_NAME
    fun getDescription() = description?.content
    fun getDirectives() = directives

    fun isMutationRequired() = mutationType != null
    fun isSubscriptionRequired() = subscriptionType != null
}
