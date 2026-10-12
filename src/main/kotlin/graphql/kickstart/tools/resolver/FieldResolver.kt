package graphql.kickstart.tools.resolver

import graphql.kickstart.tools.*
import graphql.language.FieldDefinition
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment

/**
 * @author Andrew Potter
 */
internal abstract class FieldResolver(
    val field: FieldDefinition,
    val search: FieldResolverScanner.Search,
    val options: SchemaParserOptions
) {
    val resolverInfo: ResolverInfo = search.resolverInfo
    val typeResolver = GenericTypeResolver(search.type, options)

    abstract fun scanForMatches(): List<TypeClassMatcher.PotentialMatch>

    abstract fun createDataFetcher(): DataFetcher<*>

    /**
     * Add source resolver depending on whether or not this is a resolver method
     */
    protected fun createSourceResolver(): SourceResolver {
        return if (this.search.source != null) {
            SourceResolver { _, _ -> this.search.source }
        } else {
            SourceResolver { environment, sourceObject ->
                // if source object is known, environment is null as an optimization (LightDataFetcher)
                val source = sourceObject
                    ?: environment?.getSource<Any>()
                    ?: throw ResolverError("Expected DataFetchingEnvironment and source object to not be null!")

                if (!this.typeResolver.isAssignableFrom(source.javaClass)) {
                    throw ResolverError("Expected source object to be an instance of '${this.typeResolver.getRawClass().name}' but instead got '${source.javaClass.name}'")
                }

                source
            }
        }
    }
}

fun interface SourceResolver {

    fun resolve(environment: DataFetchingEnvironment?, source: Any?): Any
}
