package graphql.kickstart.tools

import graphql.introspection.Introspection
import graphql.kickstart.tools.directive.DirectiveWiringHelper
import graphql.kickstart.tools.util.getDocumentation
import graphql.kickstart.tools.util.getExtendedFieldDefinitions
import graphql.kickstart.tools.util.unwrap
import graphql.language.*
import graphql.schema.*
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
        inputObjectDefinitions.forEach {
            if (inputObjects.none { io -> io.name == it.name }) {
                inputObjects.add(createInputObject(it, inputObjects, mutableSetOf()))
            }
        }
        val interfaces = interfaceDefinitions.map { createInterfaceObject(it, inputObjects) }
        val objects = objectDefinitions.map { createObject(it, interfaces, inputObjects) }
        val unions = unionDefinitions.map { createUnionObject(it, objects) }
        val enums = enumDefinitions.map { createEnumObject(it) }

        val directives = directiveDefinitions.map { createDirective(it, inputObjects) }.toSet()

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
        return SchemaObjects(query, mutation, subscription, types, directives, codeRegistryBuilder, rootInfo.getDescription())
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

        objectDefinition.implements.forEach { implementsDefinition ->
            val interfaceName = (implementsDefinition as TypeName).name
            builder.withInterface(interfaces.find { it.name == interfaceName }
                ?: throw SchemaError("Expected interface type with name '$interfaceName' but found none!"))
        }

        objectDefinition.getExtendedFieldDefinitions(extensionDefinitions).forEach { fieldDefinition ->
            builder.field { field ->
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

        return directiveWiringHelper.wireObject(builder.build())
    }

    private fun createInputObject(definition: InputObjectTypeDefinition, inputObjects: List<GraphQLInputObjectType>,
                                  referencingInputObjects: MutableSet<String>): GraphQLInputObjectType {
        val extensionDefinitions = inputExtensionDefinitions.filter { it.name == definition.name }

        val builder = GraphQLInputObjectType.newInputObject()
            .name(definition.name)
            .definition(definition)
            .extensionDefinitions(extensionDefinitions)
            .description(getDocumentation(definition, options))
            .withAppliedDirectives(*buildAppliedDirectives(definition.directives))

        referencingInputObjects.add(definition.name)

        (extensionDefinitions + definition).forEach {
            it.inputValueDefinitions.forEach { inputDefinition ->
                val fieldBuilder = GraphQLInputObjectField.newInputObjectField()
                    .name(inputDefinition.name)
                    .definition(inputDefinition)
                    .description(getDocumentation(inputDefinition, options))
                    .apply { inputDefinition.defaultValue?.let { v -> defaultValueLiteral(v) } }
                    .apply { getDeprecated(inputDefinition.directives)?.let { deprecate(it) } }
                    .type(determineInputType(inputDefinition.type, inputObjects, referencingInputObjects))
                    .withAppliedDirectives(*buildAppliedDirectives(inputDefinition.directives))
                builder.field(fieldBuilder.build())
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

        definition.enumValueDefinitions.forEach { enumDefinition ->
            val enumName = enumDefinition.name
            val enumValue = type.unwrap().enumConstants.find { (it as Enum<*>).name == enumName }
                ?: throw SchemaError("Expected value for name '$enumName' in enum '${type.unwrap().simpleName}' but found none!")

            val enumValueAppliedDirectives = buildAppliedDirectives(enumDefinition.directives)
            getDeprecated(enumDefinition.directives).let {
                val enumValueDefinition = GraphQLEnumValueDefinition.newEnumValueDefinition()
                    .name(enumName)
                    .description(getDocumentation(enumDefinition, options))
                    .value(enumValue)
                    .deprecationReason(it)
                    .withAppliedDirectives(*enumValueAppliedDirectives)
                    .definition(enumDefinition)
                    .build()

                builder.value(enumValueDefinition)
            }
        }

        return directiveWiringHelper.wireEnum(builder.build())
    }

    private fun createInterfaceObject(interfaceDefinition: InterfaceTypeDefinition, inputObjects: List<GraphQLInputObjectType>): GraphQLInterfaceType {
        val name = interfaceDefinition.name
        val builder = GraphQLInterfaceType.newInterface()
            .name(name)
            .definition(interfaceDefinition)
            .description(getDocumentation(interfaceDefinition, options))
            .withAppliedDirectives(*buildAppliedDirectives(interfaceDefinition.directives))

        interfaceDefinition.fieldDefinitions.forEach { fieldDefinition ->
            builder.field { field -> createField(field, fieldDefinition, inputObjects) }
        }

        interfaceDefinition.implements.forEach { implementsDefinition ->
            val interfaceName = (implementsDefinition as TypeName).name
            builder.withInterface(GraphQLTypeReference(interfaceName))
        }

        return directiveWiringHelper.wireInterFace(builder.build())
    }

    private fun createUnionObject(definition: UnionTypeDefinition, types: List<GraphQLObjectType>): GraphQLUnionType {
        val name = definition.name
        val builder = GraphQLUnionType.newUnionType()
            .name(name)
            .definition(definition)
            .description(getDocumentation(definition, options))
            .withAppliedDirectives(*buildAppliedDirectives(definition.directives))

        getLeafUnionObjects(definition, types).forEach { builder.possibleType(it) }
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
        field
            .name(fieldDefinition.name)
            .description(getDocumentation(fieldDefinition, options))
            .definition(fieldDefinition)
            .apply { getDeprecated(fieldDefinition.directives)?.let { deprecate(it) } }
            .type(determineOutputType(fieldDefinition.type, inputObjects))
            .withAppliedDirectives(*buildAppliedDirectives(fieldDefinition.directives))

        fieldDefinition.inputValueDefinitions.forEach { argumentDefinition ->
            val argumentBuilder = GraphQLArgument.newArgument()
                .name(argumentDefinition.name)
                .definition(argumentDefinition)
                .description(getDocumentation(argumentDefinition, options))
                .type(determineInputType(argumentDefinition.type, inputObjects, setOf()))
                .apply { getDeprecated(argumentDefinition.directives)?.let { deprecate(it) } }
                .apply { argumentDefinition.defaultValue?.let { defaultValueLiteral(it) } }
                .withAppliedDirectives(*buildAppliedDirectives(argumentDefinition.directives))

            field.argument(argumentBuilder.build())
        }

        return field
    }

    private fun createDirective(definition: DirectiveDefinition, inputObjects: List<GraphQLInputObjectType>): GraphQLDirective {
        val locations = definition.directiveLocations.map { Introspection.DirectiveLocation.valueOf(it.name) }.toTypedArray()

        val graphQLDirective = GraphQLDirective.newDirective()
            .name(definition.name)
            .description(getDocumentation(definition, options))
            .definition(definition)
            .comparatorRegistry(runtimeWiring.comparatorRegistry)
            .validLocations(*locations)
            .repeatable(definition.isRepeatable)
            .apply {
                definition.inputValueDefinitions.forEach { arg ->
                    argument(GraphQLArgument.newArgument()
                        .name(arg.name)
                        .definition(arg)
                        .description(getDocumentation(arg, options))
                        .type(determineInputType(arg.type, inputObjects, setOf()))
                        .apply { getDeprecated(arg.directives)?.let { deprecate(it) } }
                        .apply { arg.defaultValue?.let { defaultValueLiteral(it) } }
                        .withAppliedDirectives(*buildAppliedDirectives(arg.directives))
                        .build())
                }
            }
            .build()

        return graphQLDirective
    }

    private fun buildAppliedDirectives(directives: List<Directive>): Array<GraphQLAppliedDirective> {
        return directives.map {
            GraphQLAppliedDirective.newDirective()
                .name(it.name)
                .description(getDocumentation(it, options))
                .comparatorRegistry(runtimeWiring.comparatorRegistry)
                .apply {
                    it.arguments.forEach { arg ->
                        argument(GraphQLAppliedDirectiveArgument.newArgument()
                            .name(arg.name)
                            .type(directiveWiringHelper.buildDirectiveInputType(arg.value))
                            .valueLiteral(arg.value)
                            .build())
                    }
                }
                .build()
        }.toTypedArray()
    }

    private fun determineOutputType(typeDefinition: Type<*>, inputObjects: List<GraphQLInputObjectType>) =
        determineType(GraphQLOutputType::class, typeDefinition, permittedTypesForObject, inputObjects) as GraphQLOutputType

    private fun <T : Any> determineType(expectedType: KClass<T>, typeDefinition: Type<*>, allowedTypeReferences: Set<String>, inputObjects: List<GraphQLInputObjectType>): GraphQLType =
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

    private fun determineInputType(typeDefinition: Type<*>, inputObjects: List<GraphQLInputObjectType>, referencingInputObjects: Set<String>) =
        determineInputType(GraphQLInputType::class, typeDefinition, permittedTypesForInputObject, inputObjects, referencingInputObjects)

    private fun <T : Any> determineInputType(expectedType: KClass<T>,
                                             typeDefinition: Type<*>, allowedTypeReferences: Set<String>,
                                             inputObjects: List<GraphQLInputObjectType>,
                                             referencingInputObjects: Set<String>): GraphQLInputType =
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
                                val inputObject = createInputObject(filteredDefinitions[0], inputObjects, referencingInputObjects as MutableSet<String>)
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
