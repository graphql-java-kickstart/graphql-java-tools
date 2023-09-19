package graphql.kickstart.tools

import graphql.introspection.Introspection
import graphql.introspection.Introspection.DirectiveLocation.INPUT_FIELD_DEFINITION
import graphql.kickstart.tools.directive.DirectiveWiringHelper
import graphql.kickstart.tools.util.getDocumentation
import graphql.kickstart.tools.util.getExtendedFieldDefinitions
import graphql.kickstart.tools.util.unwrap
import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.DirectiveInfo
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.ScalarInfo
import graphql.schema.visibility.NoIntrospectionGraphqlFieldVisibility
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
    private val customScalars = scanResult.customScalars
    private val rootInfo = scanResult.rootInfo
    private val fieldResolversByType = scanResult.fieldResolversByType
    private val unusedDefinitions = scanResult.unusedDefinitions

    private val extensionDefinitions = definitions.filterIsInstance<ObjectTypeExtensionDefinition>()
    private val inputExtensionDefinitions = definitions.filterIsInstance<InputObjectTypeExtensionDefinition>()

    private val objectDefinitions = (definitions.filterIsInstance<ObjectTypeDefinition>() - extensionDefinitions)
    private val inputObjectDefinitions = (definitions.filterIsInstance<InputObjectTypeDefinition>() - inputExtensionDefinitions)
    private val enumDefinitions = definitions.filterIsInstance<EnumTypeDefinition>()
    private val interfaceDefinitions = definitions.filterIsInstance<InterfaceTypeDefinition>()
    private val directiveDefinitions = definitions.filterIsInstance<DirectiveDefinition>()

    private val unionDefinitions = definitions.filterIsInstance<UnionTypeDefinition>()

    private val permittedTypesForObject: Set<String> = (objectDefinitions.map { it.name } +
        enumDefinitions.map { it.name } +
        interfaceDefinitions.map { it.name } +
        unionDefinitions.map { it.name }).toSet()
    private val permittedTypesForInputObject: Set<String> =
        (inputObjectDefinitions.map { it.name } + enumDefinitions.map { it.name }).toSet()

    private val codeRegistryBuilder = GraphQLCodeRegistry.newCodeRegistry()
    private val directiveWiringHelper = DirectiveWiringHelper(options, runtimeWiring, codeRegistryBuilder, directiveDefinitions)

    private lateinit var schemaDirectives : Set<GraphQLDirective>

    /**
     * Parses the given schema with respect to the given dictionary and returns GraphQL objects.
     */
    fun parseSchemaObjects(): SchemaObjects {
        if (!options.introspectionEnabled) {
            codeRegistryBuilder.fieldVisibility(NoIntrospectionGraphqlFieldVisibility.NO_INTROSPECTION_FIELD_VISIBILITY)
        }
        // this overrides the above introspection enabled setting obviously... todo: add documentation
        options.fieldVisibility?.let { codeRegistryBuilder.fieldVisibility(it) }

        // Create GraphQL objects
        val inputObjects: MutableList<GraphQLInputObjectType> = mutableListOf()
        schemaDirectives = createDirectives(inputObjects)
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
        interfaces.forEach { codeRegistryBuilder.typeResolver(it, InterfaceTypeResolver(dictionary.inverse(), it)) }
        unions.forEach { codeRegistryBuilder.typeResolver(it, UnionTypeResolver(dictionary.inverse(), it)) }

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

        val types = (additionalObjects.toSet() as Set<GraphQLType>) + inputObjects + enums + interfaces + unions
        return SchemaObjects(query, mutation, subscription, types, schemaDirectives, codeRegistryBuilder, rootInfo.getDescription())
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
        val builder = GraphQLObjectType.newObject()
            .name(name)
            .definition(objectDefinition)
            .description(getDocumentation(objectDefinition, options))
            .withAppliedDirectives(*buildAppliedDirectives(objectDefinition.directives))
            .withDirectives(*buildDirectives(objectDefinition.directives, Introspection.DirectiveLocation.OBJECT))
            .apply {
                objectDefinition.implements.forEach { implementsDefinition ->
                    val interfaceName = (implementsDefinition as TypeName).name
                    withInterface(interfaces.find { it.name == interfaceName }
                        ?: throw SchemaError("Expected interface type with name '$interfaceName' but found none!"))
                }
            }
            .apply {
                objectDefinition.getExtendedFieldDefinitions(extensionDefinitions).forEach { fieldDefinition ->
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

        return directiveWiringHelper.wireObject(builder.build())
    }

    private fun createInputObject(definition: InputObjectTypeDefinition, inputObjects: List<GraphQLInputObjectType>,
                                  referencingInputObjects: MutableSet<String>): GraphQLInputObjectType {
        val extensionDefinitions = inputExtensionDefinitions.filter { it.name == definition.name }

        referencingInputObjects.add(definition.name)

        val builder = GraphQLInputObjectType.newInputObject()
            .name(definition.name)
            .definition(definition)
            .extensionDefinitions(extensionDefinitions)
            .description(getDocumentation(definition, options))
            .withAppliedDirectives(*buildAppliedDirectives(definition.directives))
            .withDirectives(*buildDirectives(definition.directives, Introspection.DirectiveLocation.INPUT_OBJECT))
            .apply {
                (extensionDefinitions + definition).forEach { typeDefinition ->
                    typeDefinition.inputValueDefinitions.forEach { fieldDefinition ->
                        field(
                            GraphQLInputObjectField.newInputObjectField()
                                .name(fieldDefinition.name)
                                .definition(fieldDefinition)
                                .description(getDocumentation(fieldDefinition, options))
                                .apply { fieldDefinition.defaultValue?.let { v -> defaultValueLiteral(v) } }
                                .apply { getDeprecated(fieldDefinition.directives)?.let { deprecate(it) } }
                                .type(determineInputType(fieldDefinition.type, inputObjects, referencingInputObjects))
                                .withAppliedDirectives(*buildAppliedDirectives(fieldDefinition.directives))
                                .withDirectives(*buildDirectives(definition.directives, INPUT_FIELD_DEFINITION))
                                .build()
                        )
                    }
                }
            }

        return directiveWiringHelper.wireInputObject(builder.build())
    }

    private fun createEnumObject(definition: EnumTypeDefinition): GraphQLEnumType {
        val name = definition.name
        val type = dictionary[definition]
            ?: throw SchemaError("Expected enum with name '$name' but found none!")
        if (!type.unwrap().isEnum) throw SchemaError("Type '$name' is declared as an enum in the GraphQL schema but is not a Java enum!")

        val builder = GraphQLEnumType.newEnum()
            .name(name)
            .definition(definition)
            .description(getDocumentation(definition, options))
            .withAppliedDirectives(*buildAppliedDirectives(definition.directives))
            .withDirectives(*buildDirectives(definition.directives, Introspection.DirectiveLocation.ENUM))
            .apply {
                definition.enumValueDefinitions.forEach { valueDefinition ->
                    val enumName = valueDefinition.name
                    val enumValue = type.unwrap().enumConstants.find { (it as Enum<*>).name == enumName }
                        ?: throw SchemaError("Expected value for name '$enumName' in enum '${type.unwrap().simpleName}' but found none!")

                    value(
                        GraphQLEnumValueDefinition.newEnumValueDefinition()
                            .name(enumName)
                            .description(getDocumentation(valueDefinition, options))
                            .value(enumValue)
                            .apply { getDeprecated(valueDefinition.directives)?.let { deprecationReason(it) } }
                            .withAppliedDirectives(*buildAppliedDirectives(valueDefinition.directives))
                            .withDirectives(*buildDirectives(valueDefinition.directives, Introspection.DirectiveLocation.ENUM_VALUE))
                            .definition(valueDefinition)
                            .build()
                    )
                }
            }

        return directiveWiringHelper.wireEnum(builder.build())
    }

    private fun createInterfaceObject(interfaceDefinition: InterfaceTypeDefinition, inputObjects: List<GraphQLInputObjectType>): GraphQLInterfaceType {
        val builder = GraphQLInterfaceType.newInterface()
            .name(interfaceDefinition.name)
            .definition(interfaceDefinition)
            .description(getDocumentation(interfaceDefinition, options))
            .withAppliedDirectives(*buildAppliedDirectives(interfaceDefinition.directives))
            .withDirectives(*buildDirectives(interfaceDefinition.directives, Introspection.DirectiveLocation.INTERFACE))
            .apply {
                interfaceDefinition.fieldDefinitions.forEach { fieldDefinition ->
                    field { field -> createField(field, fieldDefinition, inputObjects) }
                }
            }
            .apply {
                interfaceDefinition.implements.forEach { implementsDefinition ->
                    val interfaceName = (implementsDefinition as TypeName).name
                    withInterface(GraphQLTypeReference(interfaceName))
                }
            }

        return directiveWiringHelper.wireInterFace(builder.build())
    }

    private fun createUnionObject(definition: UnionTypeDefinition, types: List<GraphQLObjectType>): GraphQLUnionType {
        val builder = GraphQLUnionType.newUnionType()
            .name(definition.name)
            .definition(definition)
            .description(getDocumentation(definition, options))
            .withAppliedDirectives(*buildAppliedDirectives(definition.directives))
            .withDirectives(*buildDirectives(definition.directives, Introspection.DirectiveLocation.UNION))
            .apply {
                getLeafUnionObjects(definition, types).forEach { possibleType(it) }
            }

        return directiveWiringHelper.wireUnion(builder.build())
    }

    private fun getLeafUnionObjects(definition: UnionTypeDefinition, types: List<GraphQLObjectType>): List<GraphQLObjectType> {
        val name = definition.name
        val leafObjects = mutableListOf<GraphQLObjectType>()

        definition.memberTypes.forEach {
            val typeName = (it as TypeName).name

            // Is this a nested union? If so, expand
            val nestedUnion: UnionTypeDefinition? = unionDefinitions.find { otherDefinition -> typeName == otherDefinition.name }

            if (nestedUnion != null) {
                leafObjects.addAll(getLeafUnionObjects(nestedUnion, types))
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
            .apply { getDeprecated(fieldDefinition.directives)?.let { deprecate(it) } }
            .type(determineOutputType(fieldDefinition.type, inputObjects))
            .withAppliedDirectives(*buildAppliedDirectives(fieldDefinition.directives))
            .withDirectives(*buildDirectives(fieldDefinition.directives, Introspection.DirectiveLocation.FIELD_DEFINITION))
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
            .apply { getDeprecated(definition.directives)?.let { deprecate(it) } }
            .apply { definition.defaultValue?.let { defaultValueLiteral(it) } }
            .withAppliedDirectives(*buildAppliedDirectives(definition.directives))
            .withDirectives(*buildDirectives(definition.directives, Introspection.DirectiveLocation.ARGUMENT_DEFINITION))
            .build()
    }

    private fun createDirectives(inputObjects: MutableList<GraphQLInputObjectType>): Set<GraphQLDirective> {
        schemaDirectives = directiveDefinitions.map { definition ->
            val locations = definition.directiveLocations.map { Introspection.DirectiveLocation.valueOf(it.name) }.toTypedArray()

            GraphQLDirective.newDirective()
                .name(definition.name)
                .description(getDocumentation(definition, options))
                .definition(definition)
                .comparatorRegistry(runtimeWiring.comparatorRegistry)
                .validLocations(*locations)
                .repeatable(definition.isRepeatable)
                .apply {
                    definition.inputValueDefinitions.forEach { argumentDefinition ->
                        argument(createDirectiveArgument(argumentDefinition, inputObjects))
                    }
                }
                .build()
        }.toSet()
        // because the arguments can have directives too, we attach them only after the directives themselves are created
        schemaDirectives = schemaDirectives.map { d ->
            val arguments = d.arguments.map { a -> a.transform {
                it.withAppliedDirectives(*buildAppliedDirectives(a.definition!!.directives))
                    .withDirectives(*buildDirectives(a.definition!!.directives, Introspection.DirectiveLocation.OBJECT))
            } }
            d.transform { it.replaceArguments(arguments) }
        }.toSet()

        return schemaDirectives
    }

    private fun createDirectiveArgument(definition: InputValueDefinition, inputObjects: List<GraphQLInputObjectType>): GraphQLArgument {
        return GraphQLArgument.newArgument()
            .name(definition.name)
            .definition(definition)
            .description(getDocumentation(definition, options))
            .type(determineInputType(definition.type, inputObjects, mutableSetOf()))
            .apply { getDeprecated(definition.directives)?.let { deprecate(it) } }
            .apply { definition.defaultValue?.let { defaultValueLiteral(it) } }
            .build()
    }

    private fun buildAppliedDirectives(directives: List<Directive>): Array<GraphQLAppliedDirective> {
        return directives.map { directive ->
            val graphQLDirective = schemaDirectives.find { d -> d.name == directive.name }
                ?: DirectiveInfo.GRAPHQL_SPECIFICATION_DIRECTIVE_MAP[directive.name]
                ?: throw SchemaError("Found applied directive ${directive.name} without corresponding directive definition.")
            val graphQLArguments = graphQLDirective.arguments.associateBy { it.name }

            GraphQLAppliedDirective.newDirective()
                .name(directive.name)
                .description(getDocumentation(directive, options))
                .definition(directive)
                .comparatorRegistry(runtimeWiring.comparatorRegistry)
                .apply {
                    directive.arguments.forEach { arg ->
                        val graphQLArgument = graphQLArguments[arg.name]
                            ?: throw SchemaError("Found an unexpected directive argument ${directive.name}#${arg.name} .")
                        argument(GraphQLAppliedDirectiveArgument.newArgument()
                            .name(arg.name)
                            // TODO instead of guessing the type from its value, lookup the directive definition
                            .type(graphQLArgument.type)
                            .valueLiteral(arg.value)
                            .description(graphQLArgument.description)
                            .build()
                        )
                    }
                }
                .build()
        }.toTypedArray()
    }

    // TODO remove this once directives are fully replaced with applied directives
    private fun buildDirectives(
        directives: List<Directive>,
        directiveLocation: Introspection.DirectiveLocation
    ): Array<GraphQLDirective> {
        val names = mutableSetOf<String>()
        val output = mutableListOf<GraphQLDirective>()

        for (directive in directives) {
            val repeatable = directiveDefinitions.find { it.name.equals(directive.name) }?.isRepeatable ?: false
            if (repeatable || !names.contains(directive.name)) {
                names.add(directive.name)
                val graphQLDirective = this.schemaDirectives.find { d -> d.name == directive.name }
                    ?: DirectiveInfo.GRAPHQL_SPECIFICATION_DIRECTIVE_MAP[directive.name]
                    ?: throw SchemaError("Found applied directive ${directive.name} without corresponding directive definition.")
                val graphQLArguments = graphQLDirective.arguments.associateBy { it.name }
                output.add(
                    GraphQLDirective.newDirective()
                        .name(directive.name)
                        .description(getDocumentation(directive, options))
                        .comparatorRegistry(runtimeWiring.comparatorRegistry)
                        .validLocation(directiveLocation)
                        .repeatable(repeatable)
                        .apply {
                            directive.arguments.forEach { arg ->
                                val graphQLArgument = graphQLArguments[arg.name]
                                    ?: throw SchemaError("Found an unexpected directive argument ${directive.name}#${arg.name}.")
                                argument(GraphQLArgument.newArgument()
                                    .name(arg.name)
                                    .type(graphQLArgument.type)
                                    // TODO remove this once directives are fully replaced with applied directives
                                    .valueLiteral(arg.value)
                                    .build())
                            }
                        }
                        .build()
                )
            }
        }

        return output.toTypedArray()
    }

    private fun determineOutputType(typeDefinition: Type<*>, inputObjects: List<GraphQLInputObjectType>) =
        determineType(GraphQLOutputType::class, typeDefinition, permittedTypesForObject, inputObjects) as GraphQLOutputType

    private fun <T : Any> determineType(
        expectedType: KClass<T>,
        typeDefinition: Type<*>,
        allowedTypeReferences: Set<String>,
        inputObjects: List<GraphQLInputObjectType>
    ): GraphQLType =
        when (typeDefinition) {
            is ListType -> GraphQLList(determineType(expectedType, typeDefinition.type, allowedTypeReferences, inputObjects))
            is NonNullType -> GraphQLNonNull(determineType(expectedType, typeDefinition.type, allowedTypeReferences, inputObjects))
            is InputObjectTypeDefinition -> {
                log.info("Create input object")
                createInputObject(typeDefinition, inputObjects, mutableSetOf())
            }
            is TypeName -> {
                val scalarType = customScalars[typeDefinition.name]
                    ?: GRAPHQL_SCALARS[typeDefinition.name]
                if (scalarType != null) {
                    scalarType
                } else {
                    if (!allowedTypeReferences.contains(typeDefinition.name)) {
                        throw SchemaError("Expected type '${typeDefinition.name}' to be a ${expectedType.simpleName}, but it wasn't!  " +
                            "Was a type only permitted for object types incorrectly used as an input type, or vice-versa?")
                    }
                    inputObjects.find { it.name == typeDefinition.name } ?: GraphQLTypeReference(typeDefinition.name)
                }
            }
            else -> throw SchemaError("Unknown type: $typeDefinition")
        }

    private fun determineInputType(typeDefinition: Type<*>, inputObjects: List<GraphQLInputObjectType>, referencingInputObjects: MutableSet<String>) =
        determineInputType(GraphQLInputType::class, typeDefinition, permittedTypesForInputObject, inputObjects, referencingInputObjects)

    private fun <T : Any> determineInputType(
        expectedType: KClass<T>,
        typeDefinition: Type<*>,
        allowedTypeReferences: Set<String>,
        inputObjects: List<GraphQLInputObjectType>,
        referencingInputObjects: MutableSet<String>): GraphQLInputType =
        when (typeDefinition) {
            is ListType -> GraphQLList(determineType(expectedType, typeDefinition.type, allowedTypeReferences, inputObjects))
            is NonNullType -> GraphQLNonNull(determineType(expectedType, typeDefinition.type, allowedTypeReferences, inputObjects))
            is InputObjectTypeDefinition -> {
                log.info("Create input object")
                createInputObject(typeDefinition, inputObjects, referencingInputObjects as MutableSet<String>)
            }
            is TypeName -> {
                val scalarType = customScalars[typeDefinition.name]
                    ?: GRAPHQL_SCALARS[typeDefinition.name]
                if (scalarType != null) {
                    scalarType
                } else {
                    if (!allowedTypeReferences.contains(typeDefinition.name)) {
                        throw SchemaError("Expected type '${typeDefinition.name}' to be a ${expectedType.simpleName}, but it wasn't!  " +
                            "Was a type only permitted for object types incorrectly used as an input type, or vice-versa?")
                    }
                    val found = inputObjects.filter { it.name == typeDefinition.name }
                    if (found.size == 1) {
                        found[0]
                    } else {
                        val filteredDefinitions = inputObjectDefinitions.filter { it.name == typeDefinition.name }
                        if (filteredDefinitions.isNotEmpty()) {
                            val referencingInputObject = referencingInputObjects.find { it == typeDefinition.name }
                            if (referencingInputObject != null) {
                                GraphQLTypeReference(referencingInputObject)
                            } else {
                                val inputObject = createInputObject(filteredDefinitions[0], inputObjects, referencingInputObjects)
                                (inputObjects as MutableList).add(inputObject)
                                inputObject
                            }
                        } else {
                            // todo: handle enum type
                            GraphQLTypeReference(typeDefinition.name)
                        }
                    }
                }
            }
            else -> throw SchemaError("Unknown type: $typeDefinition")
        }

    /**
     * Returns an optional [String] describing a deprecated field/enum.
     * If a deprecation directive was defined using the @deprecated directive,
     * then a String containing either the contents of the 'reason' argument, if present, or a default
     * message defined in [DEFAULT_DEPRECATION_MESSAGE] will be returned. Otherwise, `null` will be returned
     * indicating no deprecation directive was found within the directives list.
     */
    private fun getDeprecated(directives: List<Directive>): String? =
        directives.find { it.name == "deprecated" }?.let { directive ->
            (directive.arguments.find { it.name == "reason" }?.value as? StringValue)?.value
                ?: DEFAULT_DEPRECATION_MESSAGE
        }
}

class SchemaError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

val GRAPHQL_SCALARS = ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS.associateBy { it.name }

const val DEFAULT_DEPRECATION_MESSAGE = "No longer supported"
