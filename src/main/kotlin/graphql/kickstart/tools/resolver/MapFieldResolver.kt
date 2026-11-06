package graphql.kickstart.tools.resolver

import com.fasterxml.classmate.TypeResolver
import graphql.kickstart.tools.ScanDictionary
import graphql.kickstart.tools.SchemaParserOptions
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.unwrap
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
    mapClass: JavaType
) : FieldResolver(field, search, options) {

    private val mapGenericValue: JavaType = getMapGenericType(mapClass)

    /**
     * Takes a type which implements Map and tries to find the
     * value type of that map. For some reason, mapClass is losing
     * its generics somewhere along the way and is always a raw
     * type.
     */
    private fun getMapGenericType(mapClass: JavaType): JavaType {
        val resolvedType = TypeResolver().resolve(mapClass)
        val typeParameters = resolvedType.typeParametersFor(Map::class.java)

        return typeParameters.elementAtOrElse(1) { Any::class.java }
    }

    private val returnShape by lazy {
        TypeShape.returnType(field.type, mapGenericValue, search.isSubscription, search.type, options, ScanDictionary.FieldTypeReference(field.name))
    }

    private val hasTypedValues get() = resolveTypeVariables(mapGenericValue, search.type) != Any::class.java

    override fun createDataFetcher(): DataFetcher<*> {
        val unwrapper = if (hasTypedValues) TypeShape.Unwrapper(returnShape, ResolverKind.MAP_VALUE, "${search.type.unwrap().name}.${field.name}") else null
        return MapFieldResolverDataFetcher(createSourceResolver(), field.name, unwrapper)
    }

    override fun scanForMatches(): Sequence<TypeShape> = sequenceOf(returnShape)

    override fun toString() = "MapFieldResolver{key=${field.name}}"
}

internal class MapFieldResolverDataFetcher(
    private val sourceResolver: SourceResolver,
    private val key: String,
    private val unwrapper: TypeShape.Unwrapper?
) : LightDataFetcher<Any> {

    override fun get(fieldDefinition: GraphQLFieldDefinition, sourceObject: Any?, environmentSupplier: Supplier<DataFetchingEnvironment>): Any? {
        if (sourceObject is Map<*, *>) {
            val value = sourceObject[key]
            return if (value == null || unwrapper == null) value else unwrapper.unwrap(value, environmentSupplier)
        } else {
            throw RuntimeException("MapFieldResolver attempt to fetch a field from an object instance that was not a map")
        }
    }

    override fun get(environment: DataFetchingEnvironment): Any? {
        return get(environment.fieldDefinition, sourceResolver.resolve(environment, null)) { environment }
    }
}
