package graphql.kickstart.tools.resolver

import graphql.kickstart.tools.SchemaClassScanner
import graphql.kickstart.tools.SchemaParserOptions
import graphql.kickstart.tools.TypeClassMatcher
import graphql.language.FieldDefinition
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.LightDataFetcher
import java.lang.reflect.Field
import java.util.function.Supplier

/**
 * @author Andrew Potter
 */
internal class PropertyFieldResolver(
    field: FieldDefinition,
    search: FieldResolverScanner.Search,
    options: SchemaParserOptions,
    private val property: Field
) : FieldResolver(field, search, options, property.declaringClass) {

    override fun createDataFetcher(): DataFetcher<*> {
        return PropertyFieldResolverDataFetcher(createSourceResolver(), property)
    }

    override fun scanForMatches(): List<TypeClassMatcher.PotentialMatch> {
        return listOf(
            TypeClassMatcher.PotentialMatch.returnValue(
                field.type,
                property.genericType,
                genericType,
                SchemaClassScanner.FieldTypeReference(property.toString())
            )
        )
    }

    override fun toString() = "PropertyFieldResolver{property=$property}"
}

internal class PropertyFieldResolverDataFetcher(
    private val sourceResolver: SourceResolver,
    private val field: Field
) : LightDataFetcher<Any> {

    override fun get(fieldDefinition: GraphQLFieldDefinition, sourceObject: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? {
        return field.get(sourceResolver.resolve(null, sourceObject))
    }

    override fun get(environment: DataFetchingEnvironment): Any? {
        return get(environment.fieldDefinition, sourceResolver.resolve(environment, null)) { environment }
    }
}
