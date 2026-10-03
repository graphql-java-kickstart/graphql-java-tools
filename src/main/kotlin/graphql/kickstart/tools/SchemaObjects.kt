package graphql.kickstart.tools

import graphql.schema.*

/**
 * @author Andrew Potter
 */
data class SchemaObjects @JvmOverloads constructor(
    val query: GraphQLObjectType,
    val mutation: GraphQLObjectType?,
    val subscription: GraphQLObjectType?,
    val dictionary: Set<GraphQLType>,
    val directives: Set<GraphQLDirective>,
    val codeRegistryBuilder: GraphQLCodeRegistry.Builder,
    val description: String?,
    // directives applied to the schema itself, e.g. `extend schema @link(...)`
    val schemaAppliedDirectives: List<GraphQLAppliedDirective> = emptyList()
) {
    // keeps the copy() signature from before schemaAppliedDirectives was added, for binary compatibility
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    fun copy(
        query: GraphQLObjectType = this.query,
        mutation: GraphQLObjectType? = this.mutation,
        subscription: GraphQLObjectType? = this.subscription,
        dictionary: Set<GraphQLType> = this.dictionary,
        directives: Set<GraphQLDirective> = this.directives,
        codeRegistryBuilder: GraphQLCodeRegistry.Builder = this.codeRegistryBuilder,
        description: String? = this.description
    ) = copy(
        query = query,
        mutation = mutation,
        subscription = subscription,
        dictionary = dictionary,
        directives = directives,
        codeRegistryBuilder = codeRegistryBuilder,
        description = description,
        schemaAppliedDirectives = schemaAppliedDirectives
    )

    // TODO change dictionary to Set<GraphQLNamedType> in the next major version and remove this cast
    @Suppress("UNCHECKED_CAST")
    private fun namedDictionary(): Set<GraphQLNamedType> = dictionary as Set<GraphQLNamedType>

    /**
     * Makes a GraphQLSchema with query, mutation and subscription.
     */
    fun toSchema(): GraphQLSchema {
        return GraphQLSchema.newSchema()
            .description(description)
            .query(query)
            .mutation(mutation)
            .subscription(subscription)
            .additionalTypes(namedDictionary())
            .additionalDirectives(directives)
            .withSchemaAppliedDirectives(schemaAppliedDirectives)
            .codeRegistry(codeRegistryBuilder.build())
            .build()
    }

    /**
     * Makes a GraphQLSchema with query but without mutation and subscription.
     */
    fun toReadOnlySchema(): GraphQLSchema = GraphQLSchema.newSchema()
        .description(description)
        .query(query)
        .additionalTypes(namedDictionary())
        .build()
}
