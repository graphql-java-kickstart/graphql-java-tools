package graphql.kickstart.tools.resolver

import com.fasterxml.classmate.TypeResolver
import graphql.kickstart.tools.SchemaClassScanner
import graphql.kickstart.tools.SchemaParserOptions
import graphql.kickstart.tools.TypeClassMatcher
import graphql.kickstart.tools.util.JavaType
import graphql.language.FieldDefinition
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.LightDataFetcher
import java.util.function.Supplier

/**
 * @author Nick Weedon
 *
 * The MapFieldResolver implements the Map (i.e. property map) specific portion of the logic within the GraphQL PropertyDataFetcher class.
 */
internal class MapFieldResolver(
    field: FieldDefinition,
    search: FieldResolverScanner.Search,
    options: SchemaParserOptions,
    relativeTo: JavaType
) : FieldResolver(field, search, options, relativeTo) {

    private var mapGenericValue: JavaType = getMapGenericType(relativeTo)

    /**
     * Takes a type which implements Map and tries to find the
     * value type of that map. For some reason, mapClass is losing
     * its generics somewhere along the way and is always a raw
     * type.
     */
    private fun getMapGenericType(mapClass: JavaType): JavaType {
        val resolvedType = TypeResolver().resolve(mapClass)
        val typeParameters = resolvedType.typeParametersFor(Map::class.java)

        return typeParameters.elementAtOrElse(1) { Object::class.java }
    }

    override fun createDataFetcher(): DataFetcher<*> {
        return MapFieldResolverDataFetcher(createSourceResolver(), field.name)
    }

    override fun scanForMatches(): List<TypeClassMatcher.PotentialMatch> {
        return listOf(TypeClassMatcher.PotentialMatch.returnValue(field.type, mapGenericValue, genericType, SchemaClassScanner.FieldTypeReference(field.name)))
    }

    override fun toString() = "MapFieldResolver{key=${field.name}}"
}

internal class MapFieldResolverDataFetcher(
    private val sourceResolver: SourceResolver,
    private val key: String,
) : LightDataFetcher<Any> {

    override fun get(fieldDefinition: GraphQLFieldDefinition, sourceObject: Any, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? {
        if (sourceObject is Map<*, *>) {
            return sourceObject[key]
        } else {
            throw RuntimeException("MapFieldResolver attempt to fetch a field from an object instance that was not a map")
        }
    }

    override fun get(environment: DataFetchingEnvironment): Any? {
        return get(environment.fieldDefinition, sourceResolver.resolve(environment, null), { environment })
    }
}
