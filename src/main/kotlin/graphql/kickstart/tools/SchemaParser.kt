package graphql.kickstart.tools

import graphql.introspection.Introspection
import graphql.introspection.Introspection.DirectiveLocation.ARGUMENT_DEFINITION
import graphql.introspection.Introspection.DirectiveLocation.ENUM_VALUE
import graphql.introspection.Introspection.DirectiveLocation.FIELD_DEFINITION
import graphql.introspection.Introspection.DirectiveLocation.INPUT_FIELD_DEFINITION
import graphql.kickstart.tools.directive.DirectiveWiringHelper
import graphql.kickstart.tools.util.describe
import graphql.kickstart.tools.util.getDocumentation
import graphql.kickstart.tools.util.unwrap
import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.ScalarInfo
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * Parses a GraphQL Schema and maps object fields to provided class methods.
 *
 * @author Andrew Potter
 */
class SchemaParser internal constructor(
    scanResult: ScannedSchemaObjects,
    private val options: SchemaParserOptions,
    private val runtimeWiring: RuntimeWiring
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        @JvmStatic
        fun newParser() = SchemaParserBuilder()
    }

    private val dictionary = scanResult.dictionary
    private val definitions = scanResult.definitions
    private val types = scanResult.types
    private val customScalars = scanResult.customScalars
    private val rootInfo = definitions.rootInfo
    private val resolverPlansByType = scanResult.resolverPlansByType
    private val unusedDefinitions = definitions.definitionsOf(scanResult.unusedTypes)
    private val directiveOnlyEnums = scanResult.directiveOnlyEnums

    private val objectDefinitions = types.filterIsInstance<MergedType.Object>()
    private val inputObjectDefinitions = types.filterIsInstance<MergedType.Input>()
    private val enumDefinitions = types.filterIsInstance<MergedType.Enum>()
    private val interfaceDefinitions = types.filterIsInstance<MergedType.Interface>()
    private val directiveDefinitions = definitions.directiveDefinitions

    private val unionDefinitions = types.filterIsInstance<MergedType.Union>()
    private val scalarDefinitions = types.filterIsInstance<MergedType.Scalar>().filter { it.name in customScalars }.associateBy { it.name }

    private val permittedTypesForObject: Set<String> = (objectDefinitions.map { it.name } +
        enumDefinitions.map { it.name } +
        interfaceDefinitions.map { it.name } +
        unionDefinitions.map { it.name }).toSet()
    private val permittedTypesForInputObject: Set<String> =
        (inputObjectDefinitions.map { it.name } + enumDefinitions.map { it.name }).toSet()
    private val typeReferenceErrors = TypeReferenceErrors(definitions, types, scanResult.unusedTypes)

    private val codeRegistryBuilder = GraphQLCodeRegistry.newCodeRegistry()
    private val directiveWiringHelper = DirectiveWiringHelper(runtimeWiring, codeRegistryBuilder)

    private lateinit var sdlDirectives: SdlDirectives
    private lateinit var typeBuildContext: TypeBuildContext

    /**
     * Parses the given schema with respect to the given dictionary and returns GraphQL objects.
     */
    fun parseSchemaObjects(): SchemaObjects {
        options.fieldVisibility?.let { codeRegistryBuilder.fieldVisibility(it) }

        // Create GraphQL objects
        val inputObjects: MutableList<GraphQLInputObjectType> = mutableListOf()
        sdlDirectives = SdlDirectives(directiveDefinitions, options, runtimeWiring) {
            // type references, so that an input object used by a directive applied to it doesn't contain itself
            determineType(GraphQLInputType::class, it, permittedTypesForInputObject) as GraphQLInputType
        }
        typeBuildContext = TypeBuildContext(options, sdlDirectives, customScalars)
        val scalars = scalarDefinitions.values.map { createScalar(it) }
        inputObjectDefinitions.forEach {
            if (inputObjects.none { io -> io.name == it.name }) {
                inputObjects.add(createInputObject(it, inputObjects, mutableSetOf()))
            }
        }
        val interfaces = interfaceDefinitions.map { createInterfaceObject(it, inputObjects) }
        val objects = objectDefinitions.map { createObject(it, interfaces, inputObjects) }
        val unions = unionDefinitions.map { createUnionObject(it, objects) }
        val enums = enumDefinitions.map { createEnumObject(it) }

        // Assign type resolver to interfaces now that we know all of the object types
        interfaces.forEach { codeRegistryBuilder.typeResolver(it, InterfaceTypeResolver(dictionary.typeByClass, it)) }
        unions.forEach { codeRegistryBuilder.typeResolver(it, UnionTypeResolver(dictionary.typeByClass, it)) }

        // Find query type and mutation/subscription type (if mutation/subscription type exists)
        val queryName = rootInfo.getQueryName()
        val mutationName = rootInfo.getMutationName()
        val subscriptionName = rootInfo.getSubscriptionName()

        val query = objects.find { it.name == queryName }
            ?: throw SchemaError("Expected a Query object with name '$queryName' but found none!")
        val mutation = objects.find { it.name == mutationName }
            ?: if (rootInfo.isMutationRequired()) throw SchemaError("Expected a Mutation object with name '$mutationName' but found none!") else null
        val subscription = objects.find { it.name == subscriptionName }
            ?: if (rootInfo.isSubscriptionRequired()) throw SchemaError("Expected a Subscription object with name '$subscriptionName' but found none!") else null

        val additionalObjects = objects.filter { o -> o != query && o != subscription && o != mutation }

        val types = additionalObjects.toSet<GraphQLNamedType>() + inputObjects + enums + interfaces + unions + scalars
        // Directives applied to the schema itself, e.g. `extend schema @link(...)`. Only GraphQLAppliedDirectives are built here, not
        // the deprecated GraphQLDirective ones: graphql-java doesn't resolve type references in those, so enum arguments would fail.
        val schemaAppliedDirectives = sdlDirectives.schemaAppliedDirectives(rootInfo.getDirectives())
        sdlDirectives.failOnProblems()
        val schemaObjects = SchemaObjects(query, mutation, subscription, types, sdlDirectives.definitions.toSet(), codeRegistryBuilder, rootInfo.getDescription(), schemaAppliedDirectives)
        // in the order the types were created
        val wiredObjects = if (directiveWiringHelper.isWiringRequired) wire(schemaObjects, scalars + inputObjects + interfaces + objects + unions + enums) else schemaObjects
        if (options.unmappedInputFields != UnmappedInputFields.IGNORE) {
            val wiredInputObjects = wiredObjects.dictionary.filterIsInstance<GraphQLInputObjectType>().associateBy { it.name }
            val checker = UnmappedInputFieldChecker(wiredInputObjects, options.unmappedInputFields)
            objectDefinitions.forEach { type ->
                resolverPlansByType[type].orEmpty().values.forEach { checker.check(type.name, it) }
            }
            checker.report()
        }
        return wiredObjects
    }

    /**
     * Runs the directive wirings on the types once the schema is assembled, so they see resolved types, and swaps in the types they change.
     */
    private fun wire(schemaObjects: SchemaObjects, types: List<GraphQLNamedType>): SchemaObjects {
        // building the schema resolves the type references in these types
        val wired = directiveWiringHelper.wireTypes(schemaObjects.toSchema(), types)
        if (wired.isEmpty()) return schemaObjects
        return schemaObjects.copy(
            query = wired[schemaObjects.query.name] as GraphQLObjectType? ?: schemaObjects.query,
            mutation = schemaObjects.mutation?.let { wired[it.name] as GraphQLObjectType? ?: it },
            subscription = schemaObjects.subscription?.let { wired[it.name] as GraphQLObjectType? ?: it },
            dictionary = schemaObjects.dictionary.mapTo(linkedSetOf()) { wired[it.name] ?: it }
        )
    }

    /**
     * Parses the given schema with respect to the given dictionary and returns a GraphQLSchema
     */
    fun makeExecutableSchema(): GraphQLSchema = parseSchemaObjects().toSchema()

    /**
     * Returns any unused type definitions that were found in the schema
     */
    @Suppress("unused")
    fun getUnusedDefinitions(): Set<TypeDefinition<*>> = unusedDefinitions

    private fun createObject(objectDefinition: MergedType.Object, interfaces: List<GraphQLInterfaceType>, inputObjects: List<GraphQLInputObjectType>): GraphQLObjectType {
        val builder = objectDefinition.prepare(typeBuildContext)
            .apply {
                objectDefinition.interfaces.forEach { implementsDefinition ->
                    val interfaceName = (implementsDefinition as TypeName).name
                    withInterface(interfaces.find { it.name == interfaceName }
                        ?: throw SchemaError("Expected interface type with name '$interfaceName' but found none!"))
                }
            }
            .apply {
                objectDefinition.fields.forEach { fieldDefinition ->
                    field { field ->
                        createField(field, fieldDefinition, inputObjects)
                        codeRegistryBuilder.dataFetcher(
                            FieldCoordinates.coordinates(objectDefinition.name, fieldDefinition.name),
                            resolverPlansByType[objectDefinition]?.get(fieldDefinition)?.dataFetcher()
                                ?: throw SchemaError("No resolver method found for object type '${objectDefinition.name}' and field '${fieldDefinition.name}', this is most likely a bug with graphql-java-tools")
                        )

                        val wiredField = field.build()
                        GraphQLFieldDefinition.Builder(wiredField)
                            .clearArguments()
                            .arguments(wiredField.arguments)
                    }
                }
            }

        return builder.build()
    }

    private fun createInputObject(definition: MergedType.Input, inputObjects: List<GraphQLInputObjectType>,
                                  inputObjectsInProgress: MutableSet<String>): GraphQLInputObjectType {
        val builder = definition.prepare(typeBuildContext)
        inputObjectsInProgress.add(definition.name)

        definition.fields.forEach { fieldDefinition ->
            builder.field(
                GraphQLInputObjectField.newInputObjectField()
                    .name(fieldDefinition.name)
                    .definition(fieldDefinition)
                    .description(getDocumentation(fieldDefinition, options))
                    .apply { fieldDefinition.defaultValue?.let { v -> defaultValueLiteral(v) } }
                    .apply { sdlDirectives.getDeprecated(fieldDefinition.directives)?.let { deprecate(it) } }
                    .type(determineInputType(fieldDefinition.type, inputObjects, inputObjectsInProgress))
                    .withSdlDirectives(fieldDefinition.directives, INPUT_FIELD_DEFINITION)
                    .build()
            )
        }

        return builder.build()
    }

    private fun createEnumObject(definition: MergedType.Enum): GraphQLEnumType {
        val name = definition.name
        // enums only used by directives can do without a class, see SchemaClassScanner.handleDirectiveEnum
        val enumClass = dictionary.classByType[definition]?.unwrap()
        if (enumClass == null && definition !in directiveOnlyEnums) throw SchemaError("Expected enum with name '$name' but found none!")
        if (enumClass != null && !enumClass.isEnum) throw SchemaError("Type '$name' is declared as an enum in the GraphQL schema but is not a Java enum!")

        val builder = definition.prepare(typeBuildContext)
            .apply {
                definition.values.forEach { valueDefinition ->
                    val enumName = valueDefinition.name
                    val enumValue = if (enumClass == null) {
                        enumName
                    } else {
                        enumClass.enumConstants.find { (it as Enum<*>).name == enumName }
                            ?: throw SchemaError("Expected value for name '$enumName' in enum '${enumClass.simpleName}' but found none!")
                    }

                    value(
                        GraphQLEnumValueDefinition.newEnumValueDefinition()
                            .name(enumName)
                            .description(getDocumentation(valueDefinition, options))
                            .value(enumValue)
                            .apply { sdlDirectives.getDeprecated(valueDefinition.directives)?.let { deprecationReason(it) } }
                            .withSdlDirectives(valueDefinition.directives, ENUM_VALUE)
                            .definition(valueDefinition)
                            .build()
                    )
                }
            }

        return builder.build()
    }

    private fun createScalar(definition: MergedType.Scalar): GraphQLScalarType = definition.prepare(typeBuildContext).build()

    private fun createInterfaceObject(interfaceDefinition: MergedType.Interface, inputObjects: List<GraphQLInputObjectType>): GraphQLInterfaceType {
        val builder = interfaceDefinition.prepare(typeBuildContext)
            .apply {
                interfaceDefinition.fields.forEach { fieldDefinition ->
                    field { field -> createField(field, fieldDefinition, inputObjects) }
                }
            }
            .apply {
                interfaceDefinition.interfaces.forEach { implementsDefinition ->
                    val interfaceName = (implementsDefinition as TypeName).name
                    withInterface(GraphQLTypeReference(interfaceName))
                }
            }

        return builder.build()
    }

    private fun createUnionObject(definition: MergedType.Union, types: List<GraphQLObjectType>): GraphQLUnionType {
        val builder = definition.prepare(typeBuildContext)
            .apply {
                getLeafUnionObjects(definition, types).forEach { possibleType(it) }
            }

        return builder.build()
    }

    private fun getLeafUnionObjects(
        definition: MergedType.Union,
        types: List<GraphQLObjectType>,
        path: List<MergedType.Union> = listOf(definition),
    ): List<GraphQLObjectType> {
        val name = definition.name
        val leafObjects = mutableListOf<GraphQLObjectType>()

        definition.members.forEach {
            val typeName = (it as TypeName).name

            // Is this a nested union? If so, expand
            val nestedUnion: MergedType.Union? = unionDefinitions.find { otherDefinition -> typeName == otherDefinition.name }

            if (nestedUnion != null) {
                if (nestedUnion in path) {
                    val cycle = (path.dropWhile { union -> union != nestedUnion } + nestedUnion).joinToString(" -> ") { union -> union.name }
                    throw SchemaError("Union '$typeName' contains itself through $cycle${it.sourceLocation.describe()}.")
                }
                leafObjects.addAll(getLeafUnionObjects(nestedUnion, types, path + nestedUnion))
            } else {
                leafObjects.add(types.find { type -> type.name == typeName }
                    ?: throw SchemaError("Expected object type '$typeName' for union type '$name', but found none!"))
            }
        }
        return leafObjects
    }

    private fun createField(field: GraphQLFieldDefinition.Builder, fieldDefinition: FieldDefinition, inputObjects: List<GraphQLInputObjectType>): GraphQLFieldDefinition.Builder {
        return field
            .name(fieldDefinition.name)
            .description(getDocumentation(fieldDefinition, options))
            .definition(fieldDefinition)
            .apply { sdlDirectives.getDeprecated(fieldDefinition.directives)?.let { deprecate(it) } }
            .type(determineOutputType(fieldDefinition.type))
            .withSdlDirectives(fieldDefinition.directives, FIELD_DEFINITION)
            .apply {
                fieldDefinition.inputValueDefinitions.forEach { argumentDefinition ->
                    argument(createArgument(argumentDefinition, inputObjects))
                }
            }
    }

    private fun createArgument(definition: InputValueDefinition, inputObjects: List<GraphQLInputObjectType>): GraphQLArgument {
        return GraphQLArgument.newArgument()
            .name(definition.name)
            .definition(definition)
            .description(getDocumentation(definition, options))
            .type(determineInputType(definition.type, inputObjects, mutableSetOf()))
            .apply { sdlDirectives.getDeprecated(definition.directives)?.let { deprecate(it) } }
            .apply { definition.defaultValue?.let { defaultValueLiteral(it) } }
            .withSdlDirectives(definition.directives, ARGUMENT_DEFINITION)
            .build()
    }

    private fun <B : GraphqlDirectivesContainerTypeBuilder<B, B>> B.withSdlDirectives(directives: List<Directive>, location: Introspection.DirectiveLocation): B =
        with(sdlDirectives) { withSdlDirectives(directives, location) }

    // custom scalars are in the dictionary, so they're referenced by name like the other types
    private fun scalarType(name: String?): GraphQLInputType? =
        scalarDefinitions[name]?.let { GraphQLTypeReference(it.name) } ?: GRAPHQL_SCALARS[name]

    private fun determineOutputType(typeDefinition: Type<*>) =
        determineType(GraphQLOutputType::class, typeDefinition, permittedTypesForObject) as GraphQLOutputType

    private fun <T : Any> determineType(
        expectedType: KClass<T>,
        typeDefinition: Type<*>,
        allowedTypeReferences: Set<String>,
        namedType: (TypeName) -> GraphQLType = { GraphQLTypeReference(it.name) }
    ): GraphQLType =
        when (typeDefinition) {
            is ListType -> GraphQLList(determineType(expectedType, typeDefinition.type, allowedTypeReferences, namedType))
            is NonNullType -> GraphQLNonNull(determineType(expectedType, typeDefinition.type, allowedTypeReferences, namedType))
            is TypeName -> {
                val scalarType = scalarType(typeDefinition.name)
                if (scalarType != null) {
                    scalarType
                } else {
                    if (!allowedTypeReferences.contains(typeDefinition.name)) {
                        throw typeReferenceErrors.unexpectedTypeError(typeDefinition, expectedType)
                    }
                    namedType(typeDefinition)
                }
            }
            else -> throw SchemaError("Unknown type: $typeDefinition")
        }

    private fun determineInputType(typeDefinition: Type<*>, inputObjects: List<GraphQLInputObjectType>, inputObjectsInProgress: MutableSet<String>) =
        determineType(GraphQLInputType::class, typeDefinition, permittedTypesForInputObject) {
            inputObjectType(it, inputObjects, inputObjectsInProgress)
        } as GraphQLInputType

    private fun inputObjectType(typeName: TypeName, inputObjects: List<GraphQLInputObjectType>, inputObjectsInProgress: MutableSet<String>): GraphQLType {
        inputObjects.find { it.name == typeName.name }?.let { return it }
        val definition = inputObjectDefinitions.find { it.name == typeName.name }
        if (definition == null || typeName.name in inputObjectsInProgress) return GraphQLTypeReference(typeName.name)
        return createInputObject(definition, inputObjects, inputObjectsInProgress).also { (inputObjects as MutableList).add(it) }
    }

}

class SchemaError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

val GRAPHQL_SCALARS = ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS.associateBy { it.name }

const val DEFAULT_DEPRECATION_MESSAGE = "No longer supported"
