package graphql.kickstart.tools

import graphql.Directives
import graphql.introspection.Introspection
import graphql.introspection.Introspection.DirectiveLocation.ARGUMENT_DEFINITION
import graphql.introspection.Introspection.DirectiveLocation.ENUM
import graphql.introspection.Introspection.DirectiveLocation.ENUM_VALUE
import graphql.introspection.Introspection.DirectiveLocation.FIELD_DEFINITION
import graphql.introspection.Introspection.DirectiveLocation.INPUT_FIELD_DEFINITION
import graphql.introspection.Introspection.DirectiveLocation.INPUT_OBJECT
import graphql.introspection.Introspection.DirectiveLocation.INTERFACE
import graphql.introspection.Introspection.DirectiveLocation.OBJECT
import graphql.introspection.Introspection.DirectiveLocation.SCALAR
import graphql.introspection.Introspection.DirectiveLocation.UNION
import graphql.kickstart.tools.directive.DirectiveWiringHelper
import graphql.kickstart.tools.resolver.MethodFieldResolver
import graphql.kickstart.tools.util.describe
import graphql.kickstart.tools.util.extensionsOf
import graphql.kickstart.tools.util.getDocumentation
import graphql.kickstart.tools.util.unwrap
import graphql.kickstart.tools.util.withExtensions
import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.ScalarInfo
import graphql.schema.visibility.BlockedFields
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
    private val typeExtensions = scanResult.typeExtensions
    private val customScalars = scanResult.customScalars
    private val rootInfo = scanResult.rootInfo
    private val fieldResolversByType = scanResult.fieldResolversByType
    private val unusedDefinitions = scanResult.unusedDefinitions
    private val directiveOnlyEnums = scanResult.directiveOnlyEnums

    private val objectDefinitions = definitions.filterIsInstance<ObjectTypeDefinition>()
    private val inputObjectDefinitions = definitions.filterIsInstance<InputObjectTypeDefinition>()
    private val enumDefinitions = definitions.filterIsInstance<EnumTypeDefinition>()
    private val interfaceDefinitions = definitions.filterIsInstance<InterfaceTypeDefinition>()
    private val directiveDefinitions = definitions.filterIsInstance<DirectiveDefinition>()

    private val unionDefinitions = definitions.filterIsInstance<UnionTypeDefinition>()
    private val scalarDefinitions = definitions.filterIsInstance<ScalarTypeDefinition>().filter { it.name in customScalars }.associateBy { it.name }

    private val permittedTypesForObject: Set<String> = (objectDefinitions.map { it.name } +
        enumDefinitions.map { it.name } +
        interfaceDefinitions.map { it.name } +
        unionDefinitions.map { it.name }).toSet()
    private val permittedTypesForInputObject: Set<String> =
        (inputObjectDefinitions.map { it.name } + enumDefinitions.map { it.name }).toSet()
    private val typeReferenceErrors = TypeReferenceErrors(definitions, unusedDefinitions, typeExtensions)

    private val codeRegistryBuilder = GraphQLCodeRegistry.newCodeRegistry()
    private val directiveWiringHelper = DirectiveWiringHelper(runtimeWiring, codeRegistryBuilder)

    private lateinit var sdlDirectives: SdlDirectives

    /**
     * Parses the given schema with respect to the given dictionary and returns GraphQL objects.
     */
    fun parseSchemaObjects(): SchemaObjects {
        @Suppress("DEPRECATION")
        if (!options.introspectionEnabled) {
            // What the deprecated NoIntrospectionGraphqlFieldVisibility does. Its suggested replacement,
            // Introspection.enabledJvmWide, would also turn introspection off for every other schema in the JVM.
            // TODO remove this in 15.0.0 (#858)
            codeRegistryBuilder.fieldVisibility(BlockedFields.newBlock().addPattern("__.*").build())
        }
        // this overrides the above introspection enabled setting obviously... todo: add documentation
        options.fieldVisibility?.let { codeRegistryBuilder.fieldVisibility(it) }

        // Create GraphQL objects
        val inputObjects: MutableList<GraphQLInputObjectType> = mutableListOf()
        sdlDirectives = SdlDirectives(directiveDefinitions, options, runtimeWiring, log) {
            // type references, so that an input object used by a directive applied to it doesn't contain itself
            determineType(GraphQLInputType::class, it, permittedTypesForInputObject) as GraphQLInputType
        }
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

        val types = (additionalObjects.toSet() as Set<GraphQLType>) + inputObjects + enums + interfaces + unions + scalars
        // Directives applied to the schema itself, e.g. `extend schema @link(...)`. Only GraphQLAppliedDirectives are built here, not
        // the deprecated GraphQLDirective ones: graphql-java doesn't resolve type references in those, so enum arguments would fail.
        val schemaAppliedDirectives = sdlDirectives.schemaAppliedDirectives(rootInfo.getDirectives())
        val schemaObjects = SchemaObjects(query, mutation, subscription, types, sdlDirectives.definitions.toSet(), codeRegistryBuilder, rootInfo.getDescription(), schemaAppliedDirectives)
        // in the order the types were created
        val wiredObjects = if (directiveWiringHelper.isWiringRequired) wire(schemaObjects, scalars + inputObjects + interfaces + objects + unions + enums) else schemaObjects
        if (options.unmappedInputFields != UnmappedInputFields.IGNORE) {
            val wiredInputObjects = wiredObjects.dictionary.filterIsInstance<GraphQLInputObjectType>().associateBy { it.name }
            val checker = UnmappedInputFieldChecker(wiredInputObjects, options.unmappedInputFields)
            objectDefinitions.forEach { type ->
                fieldResolversByType[type].orEmpty().values.filterIsInstance<MethodFieldResolver>().forEach { checker.check(type.name, it) }
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
            dictionary = schemaObjects.dictionary.mapTo(linkedSetOf()) { wired[(it as GraphQLNamedType).name] ?: it }
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

    private fun createObject(objectDefinition: ObjectTypeDefinition, interfaces: List<GraphQLInterfaceType>, inputObjects: List<GraphQLInputObjectType>): GraphQLObjectType {
        val name = objectDefinition.name
        val extensions = typeExtensions.extensionsOf<ObjectTypeExtensionDefinition>(name)
        val directives = sdlDirectives.directivesOf(objectDefinition, extensions)
        val fieldDefinitions = objectDefinition.withExtensions(typeExtensions).flatMap { it.fieldDefinitions }
        warnRepeatedFields(name, fieldDefinitions)
        val builder = GraphQLObjectType.newObject()
            .name(name)
            .definition(objectDefinition)
            .extensionDefinitions(extensions)
            .description(getDocumentation(objectDefinition, options))
            .withSdlDirectives(directives, OBJECT)
            .apply {
                objectDefinition.withExtensions(typeExtensions).flatMap { it.implements }.forEach { implementsDefinition ->
                    val interfaceName = (implementsDefinition as TypeName).name
                    withInterface(interfaces.find { it.name == interfaceName }
                        ?: throw SchemaError("Expected interface type with name '$interfaceName' but found none!"))
                }
            }
            .apply {
                fieldDefinitions.forEach { fieldDefinition ->
                    field { field ->
                        createField(field, fieldDefinition, inputObjects)
                        codeRegistryBuilder.dataFetcher(
                            FieldCoordinates.coordinates(objectDefinition.name, fieldDefinition.name),
                            fieldResolversByType[objectDefinition]?.get(fieldDefinition)?.createDataFetcher()
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

    private fun createInputObject(definition: InputObjectTypeDefinition, inputObjects: List<GraphQLInputObjectType>,
                                  inputObjectsInProgress: MutableSet<String>): GraphQLInputObjectType {
        val extensionDefinitions = typeExtensions.extensionsOf<InputObjectTypeExtensionDefinition>(definition.name)
        val directives = sdlDirectives.directivesOf(definition, extensionDefinitions)
        val fieldDefinitions = (extensionDefinitions + definition).flatMap { it.inputValueDefinitions }
        warnRepeatedFields(definition.name, fieldDefinitions)

        inputObjectsInProgress.add(definition.name)

        val builder = GraphQLInputObjectType.newInputObject()
            .name(definition.name)
            .definition(definition)
            .extensionDefinitions(extensionDefinitions)
            .description(getDocumentation(definition, options))
            .withSdlDirectives(directives, INPUT_OBJECT)
            .apply {
                fieldDefinitions.forEach { fieldDefinition ->
                    field(
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
            }

        return builder.build()
    }

    private fun createEnumObject(definition: EnumTypeDefinition): GraphQLEnumType {
        val name = definition.name
        // enums only used by directives can do without a class, see SchemaClassScanner.handleDirectiveEnum
        val enumClass = dictionary.classByType[definition]?.unwrap()
        if (enumClass == null && definition !in directiveOnlyEnums) throw SchemaError("Expected enum with name '$name' but found none!")
        if (enumClass != null && !enumClass.isEnum) throw SchemaError("Type '$name' is declared as an enum in the GraphQL schema but is not a Java enum!")

        val extensions = typeExtensions.extensionsOf<EnumTypeExtensionDefinition>(name)
        val directives = sdlDirectives.directivesOf(definition, extensions)
        val values = definition.withExtensions(typeExtensions).flatMap { it.enumValueDefinitions }
        warnRepeated(name, "value", values.map { it.name })
        val builder = GraphQLEnumType.newEnum()
            .name(name)
            .definition(definition)
            .extensionDefinitions(extensions)
            .description(getDocumentation(definition, options))
            .withSdlDirectives(directives, ENUM)
            .apply {
                values.distinctBy { it.name }.forEach { valueDefinition ->
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

    private fun createScalar(definition: ScalarTypeDefinition): GraphQLScalarType {
        val name = definition.name
        val provided = customScalars.getValue(name)
        val extensions = typeExtensions.extensionsOf<ScalarTypeExtensionDefinition>(name)
        val directives = sdlDirectives.directivesOf(definition, extensions)
        val builder = GraphQLScalarType.newScalar(provided)
            .definition(definition)
            .extensionDefinitions(extensions)
            .description(getDocumentation(definition, options) ?: provided.description)
            .specifiedByUrl(getSpecifiedByUrl(directives) ?: provided.specifiedByUrl)
            // like graphql-java, only the schema's directives are applied
            .clearDirectives()
            .withSdlDirectives(directives, SCALAR)

        return builder.build()
    }

    private fun createInterfaceObject(interfaceDefinition: InterfaceTypeDefinition, inputObjects: List<GraphQLInputObjectType>): GraphQLInterfaceType {
        val extensions = typeExtensions.extensionsOf<InterfaceTypeExtensionDefinition>(interfaceDefinition.name)
        val directives = sdlDirectives.directivesOf(interfaceDefinition, extensions)
        val fieldDefinitions = interfaceDefinition.withExtensions(typeExtensions).flatMap { it.fieldDefinitions }
        warnRepeated(interfaceDefinition.name, "field", fieldDefinitions.map { it.name })
        val builder = GraphQLInterfaceType.newInterface()
            .name(interfaceDefinition.name)
            .definition(interfaceDefinition)
            .extensionDefinitions(extensions)
            .description(getDocumentation(interfaceDefinition, options))
            .withSdlDirectives(directives, INTERFACE)
            .apply {
                fieldDefinitions.distinctBy { it.name }.forEach { fieldDefinition ->
                    field { field -> createField(field, fieldDefinition, inputObjects) }
                }
            }
            .apply {
                interfaceDefinition.withExtensions(typeExtensions).flatMap { it.implements }.forEach { implementsDefinition ->
                    val interfaceName = (implementsDefinition as TypeName).name
                    withInterface(GraphQLTypeReference(interfaceName))
                }
            }

        return builder.build()
    }

    private fun createUnionObject(definition: UnionTypeDefinition, types: List<GraphQLObjectType>): GraphQLUnionType {
        val extensions = typeExtensions.extensionsOf<UnionTypeExtensionDefinition>(definition.name)
        val directives = sdlDirectives.directivesOf(definition, extensions)
        warnRepeated(definition.name, "member", definition.withExtensions(typeExtensions).flatMap { it.memberTypes }.map { (it as TypeName).name })
        val builder = GraphQLUnionType.newUnionType()
            .name(definition.name)
            .definition(definition)
            .extensionDefinitions(extensions)
            .description(getDocumentation(definition, options))
            .withSdlDirectives(directives, UNION)
            .apply {
                getLeafUnionObjects(definition, types).forEach { possibleType(it) }
            }

        return builder.build()
    }

    private fun getLeafUnionObjects(
        definition: UnionTypeDefinition,
        types: List<GraphQLObjectType>,
        path: List<UnionTypeDefinition> = listOf(definition),
    ): List<GraphQLObjectType> {
        val name = definition.name
        val leafObjects = mutableListOf<GraphQLObjectType>()

        definition.withExtensions(typeExtensions).flatMap { it.memberTypes }.forEach {
            val typeName = (it as TypeName).name

            // Is this a nested union? If so, expand
            val nestedUnion: UnionTypeDefinition? = unionDefinitions.find { otherDefinition -> typeName == otherDefinition.name }

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

    // TODO throw a SchemaError instead in 15.0.0 (#863), like graphql-java does
    private fun warnRepeated(typeName: String, kind: String, names: List<String?>) {
        names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            log.warn("Type '$typeName' declares $kind '$it' more than once, only the first one is kept.")
        }
    }

    // TODO throw a SchemaError instead in 15.0.0 (#865), like graphql-java does
    private fun warnRepeatedFields(typeName: String, fieldDefinitions: List<NamedNode<*>>) {
        fieldDefinitions.groupBy { it.name }.values.filter { it.size > 1 }.forEach { declarations ->
            val kept = declarations.last()
            val location = kept.sourceLocation.describe()
            val which = if (location.isEmpty()) "one of them" else "the one$location"
            log.warn("Type '$typeName' declares field '${kept.name}' more than once, only $which is kept.")
        }
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

    private fun getSpecifiedByUrl(directives: List<Directive>): String? =
        (directives.find { it.name == Directives.SpecifiedByDirective.name }?.getArgument("url")?.value as? StringValue)?.value

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
