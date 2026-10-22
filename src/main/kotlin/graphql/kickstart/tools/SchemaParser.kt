package graphql.kickstart.tools

import graphql.Directives
import graphql.Scalars
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
import graphql.introspection.Introspection.DirectiveLocation.SCHEMA
import graphql.introspection.Introspection.DirectiveLocation.UNION
import graphql.kickstart.tools.directive.DirectiveWiringHelper
import graphql.kickstart.tools.resolver.MethodFieldResolver
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

    private val codeRegistryBuilder = GraphQLCodeRegistry.newCodeRegistry()
    private val directiveWiringHelper = DirectiveWiringHelper(runtimeWiring, codeRegistryBuilder)

    // by name, in the order they're declared
    private lateinit var schemaDirectives: MutableMap<String, GraphQLDirective>

    /**
     * Parses the given schema with respect to the given dictionary and returns GraphQL objects.
     */
    fun parseSchemaObjects(): SchemaObjects {
        if (!options.introspectionEnabled) {
            // What the deprecated NoIntrospectionGraphqlFieldVisibility does. Its suggested replacement,
            // Introspection.enabledJvmWide, would also turn introspection off for every other schema in the JVM.
            // TODO deprecate introspectionEnabled and remove this in 15.0.0 (#858)
            codeRegistryBuilder.fieldVisibility(BlockedFields.newBlock().addPattern("__.*").build())
        }
        // this overrides the above introspection enabled setting obviously... todo: add documentation
        options.fieldVisibility?.let { codeRegistryBuilder.fieldVisibility(it) }

        // Create GraphQL objects
        val inputObjects: MutableList<GraphQLInputObjectType> = mutableListOf()
        createDirectives()
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

        val types = (additionalObjects.toSet() as Set<GraphQLType>) + inputObjects + enums + interfaces + unions + scalars
        // Directives applied to the schema itself, e.g. `extend schema @link(...)`. Only GraphQLAppliedDirectives are built here, not
        // the deprecated GraphQLDirective ones: graphql-java doesn't resolve type references in those, so enum arguments would fail.
        val schemaAppliedDirectives = buildAppliedDirectives(rootInfo.getDirectives(), SCHEMA).toList()
        val schemaObjects = SchemaObjects(query, mutation, subscription, types, schemaDirectives.values.toSet(), codeRegistryBuilder, rootInfo.getDescription(), schemaAppliedDirectives)
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
        val directives = directivesOf(objectDefinition, extensions)
        val builder = GraphQLObjectType.newObject()
            .name(name)
            .definition(objectDefinition)
            .extensionDefinitions(extensions)
            .description(getDocumentation(objectDefinition, options))
            .withAppliedDirectives(*buildAppliedDirectives(directives, OBJECT))
            .withDirectives(*buildDirectives(directives, OBJECT))
            .apply {
                objectDefinition.withExtensions(typeExtensions).flatMap { it.implements }.forEach { implementsDefinition ->
                    val interfaceName = (implementsDefinition as TypeName).name
                    withInterface(interfaces.find { it.name == interfaceName }
                        ?: throw SchemaError("Expected interface type with name '$interfaceName' but found none!"))
                }
            }
            .apply {
                objectDefinition.withExtensions(typeExtensions).flatMap { it.fieldDefinitions }.forEach { fieldDefinition ->
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
        val directives = directivesOf(definition, extensionDefinitions)

        inputObjectsInProgress.add(definition.name)

        val builder = GraphQLInputObjectType.newInputObject()
            .name(definition.name)
            .definition(definition)
            .extensionDefinitions(extensionDefinitions)
            .description(getDocumentation(definition, options))
            .withAppliedDirectives(*buildAppliedDirectives(directives, INPUT_OBJECT))
            .withDirectives(*buildDirectives(directives, INPUT_OBJECT))
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
                                .type(determineInputType(fieldDefinition.type, inputObjects, inputObjectsInProgress))
                                .withAppliedDirectives(*buildAppliedDirectives(fieldDefinition.directives, INPUT_FIELD_DEFINITION))
                                .withDirectives(*buildDirectives(fieldDefinition.directives, INPUT_FIELD_DEFINITION))
                                .build()
                        )
                    }
                }
            }

        return builder.build()
    }

    private fun createEnumObject(definition: EnumTypeDefinition): GraphQLEnumType {
        val name = definition.name
        // enums only used by directives can do without a class, see SchemaClassScanner.handleDirectiveEnum
        val enumClass = dictionary[definition]?.unwrap()
        if (enumClass == null && definition !in directiveOnlyEnums) throw SchemaError("Expected enum with name '$name' but found none!")
        if (enumClass != null && !enumClass.isEnum) throw SchemaError("Type '$name' is declared as an enum in the GraphQL schema but is not a Java enum!")

        val extensions = typeExtensions.extensionsOf<EnumTypeExtensionDefinition>(name)
        val directives = directivesOf(definition, extensions)
        val values = definition.withExtensions(typeExtensions).flatMap { it.enumValueDefinitions }
        warnRepeated(name, "value", values.map { it.name })
        val builder = GraphQLEnumType.newEnum()
            .name(name)
            .definition(definition)
            .extensionDefinitions(extensions)
            .description(getDocumentation(definition, options))
            .withAppliedDirectives(*buildAppliedDirectives(directives, ENUM))
            .withDirectives(*buildDirectives(directives, ENUM))
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
                            .apply { getDeprecated(valueDefinition.directives)?.let { deprecationReason(it) } }
                            .withAppliedDirectives(*buildAppliedDirectives(valueDefinition.directives, ENUM_VALUE))
                            .withDirectives(*buildDirectives(valueDefinition.directives, ENUM_VALUE))
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
        val directives = directivesOf(definition, extensions)
        val (appliedDirectives, legacyDirectives) = try {
            buildAppliedDirectives(directives, SCALAR) to buildDirectives(directives, SCALAR)
        } catch (e: SchemaError) {
            // TODO throw instead in 15.0.0 (#893), like for the other types
            log.warn("Scalar '$name' is built without its directives from the schema: ${e.message}")
            emptyArray<GraphQLAppliedDirective>() to emptyArray<GraphQLDirective>()
        }
        val builder = GraphQLScalarType.newScalar(provided)
            .definition(definition)
            .extensionDefinitions(extensions)
            .description(getDocumentation(definition, options) ?: provided.description)
            .specifiedByUrl(getSpecifiedByUrl(directives) ?: provided.specifiedByUrl)
            // like graphql-java, only the schema's directives are applied
            .clearDirectives()
            .withAppliedDirectives(*appliedDirectives)
            .withDirectives(*legacyDirectives)

        return builder.build()
    }

    private fun createInterfaceObject(interfaceDefinition: InterfaceTypeDefinition, inputObjects: List<GraphQLInputObjectType>): GraphQLInterfaceType {
        val extensions = typeExtensions.extensionsOf<InterfaceTypeExtensionDefinition>(interfaceDefinition.name)
        val directives = directivesOf(interfaceDefinition, extensions)
        val fieldDefinitions = interfaceDefinition.withExtensions(typeExtensions).flatMap { it.fieldDefinitions }
        warnRepeated(interfaceDefinition.name, "field", fieldDefinitions.map { it.name })
        val builder = GraphQLInterfaceType.newInterface()
            .name(interfaceDefinition.name)
            .definition(interfaceDefinition)
            .extensionDefinitions(extensions)
            .description(getDocumentation(interfaceDefinition, options))
            .withAppliedDirectives(*buildAppliedDirectives(directives, INTERFACE))
            .withDirectives(*buildDirectives(directives, INTERFACE))
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
        val directives = directivesOf(definition, extensions)
        warnRepeated(definition.name, "member", definition.withExtensions(typeExtensions).flatMap { it.memberTypes }.map { (it as TypeName).name })
        val builder = GraphQLUnionType.newUnionType()
            .name(definition.name)
            .definition(definition)
            .extensionDefinitions(extensions)
            .description(getDocumentation(definition, options))
            .withAppliedDirectives(*buildAppliedDirectives(directives, UNION))
            .withDirectives(*buildDirectives(directives, UNION))
            .apply {
                getLeafUnionObjects(definition, types).forEach { possibleType(it) }
            }

        return builder.build()
    }

    private fun getLeafUnionObjects(definition: UnionTypeDefinition, types: List<GraphQLObjectType>): List<GraphQLObjectType> {
        val name = definition.name
        val leafObjects = mutableListOf<GraphQLObjectType>()

        definition.withExtensions(typeExtensions).flatMap { it.memberTypes }.forEach {
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

    // TODO throw a SchemaError instead in 15.0.0 (#863), like graphql-java does
    private fun warnRepeated(typeName: String, kind: String, names: List<String?>) {
        names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            log.warn("Type '$typeName' declares $kind '$it' more than once, only the first one is kept.")
        }
    }

    private fun directivesOf(definition: TypeDefinition<*>, extensions: List<TypeDefinition<*>>): List<Directive> {
        val applied = definition.directives.mapTo(mutableSetOf()) { it.name }
        return definition.directives + extensions.flatMap { extension ->
            val (repeated, kept) = extension.directives.partition { it.name in applied && isNonRepeatable(it.name) }
            repeated.forEach { log.warn("Directive @${it.name} isn't repeatable but an extension of type '${definition.name}' applies it again, only the first one is kept.") }
            applied += kept.map { it.name }
            kept
        }
    }

    private fun isNonRepeatable(directiveName: String): Boolean =
        directiveDefinitions.find { it.name == directiveName }?.let { !it.isRepeatable } ?: BUILT_IN_DIRECTIVES.containsKey(directiveName)

    private fun createField(field: GraphQLFieldDefinition.Builder, fieldDefinition: FieldDefinition, inputObjects: List<GraphQLInputObjectType>): GraphQLFieldDefinition.Builder {
        return field
            .name(fieldDefinition.name)
            .description(getDocumentation(fieldDefinition, options))
            .definition(fieldDefinition)
            .apply { getDeprecated(fieldDefinition.directives)?.let { deprecate(it) } }
            .type(determineOutputType(fieldDefinition.type))
            .withAppliedDirectives(*buildAppliedDirectives(fieldDefinition.directives, FIELD_DEFINITION))
            .withDirectives(*buildDirectives(fieldDefinition.directives, FIELD_DEFINITION))
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
            .withAppliedDirectives(*buildAppliedDirectives(definition.directives, ARGUMENT_DEFINITION))
            .withDirectives(*buildDirectives(definition.directives, ARGUMENT_DEFINITION))
            .build()
    }

    private fun createDirectives() {
        schemaDirectives = directiveDefinitions.associateTo(linkedMapOf()) { it.name to createDirective(it) }
        // because the arguments can have directives too, we attach them only after the directives themselves are created
        schemaDirectives.replaceAll { _, d ->
            val arguments = d.arguments.map { a -> a.transform {
                it.withAppliedDirectives(*buildAppliedDirectives(a.definition!!.directives, ARGUMENT_DEFINITION))
                    .withDirectives(*buildDirectives(a.definition!!.directives, ARGUMENT_DEFINITION))
            } }
            d.transform { it.replaceArguments(arguments) }
        }
    }

    private fun createDirective(definition: DirectiveDefinition): GraphQLDirective {
        val locations = definition.directiveLocations.map { Introspection.DirectiveLocation.valueOf(it.name) }.toTypedArray()

        return GraphQLDirective.newDirective()
            .name(definition.name)
            .description(getDocumentation(definition, options))
            .definition(definition)
            .comparatorRegistry(runtimeWiring.comparatorRegistry)
            .validLocations(*locations)
            .repeatable(definition.isRepeatable)
            .apply {
                definition.inputValueDefinitions.forEach { argumentDefinition ->
                    argument(createDirectiveArgument(argumentDefinition))
                }
            }
            .build()
    }

    private fun createDirectiveArgument(definition: InputValueDefinition): GraphQLArgument {
        return GraphQLArgument.newArgument()
            .name(definition.name)
            .definition(definition)
            .description(getDocumentation(definition, options))
            // type references, so that an input object used by a directive applied to it doesn't contain itself
            .type(determineType(GraphQLInputType::class, definition.type, permittedTypesForInputObject) as GraphQLInputType)
            .apply { getDeprecated(definition.directives)?.let { deprecate(it) } }
            .apply { definition.defaultValue?.let { defaultValueLiteral(it) } }
            .build()
    }

    private fun buildAppliedDirectives(directives: List<Directive>, location: Introspection.DirectiveLocation): Array<GraphQLAppliedDirective> {
        // TODO throw a SchemaError instead in 15.0.0 (#884), like graphql-java does
        directiveProblems(directives, location).forEach { log.warn(it) }
        return directives.map { directive ->
            val graphQLDirective = findDirective(directive.name)
                ?: if (options.allowUndeclaredDirectives) return@map buildUndeclaredAppliedDirective(directive)
                else throw SchemaError("Found applied directive ${directive.name} without corresponding directive definition.")
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
                .apply {
                    // arguments that weren't supplied get the default from the directive definition, like graphql-java does.
                    // this also gives a bare @deprecated its "reason", without which SchemaPrinter throws a NPE.
                    missingArgumentsWithDefault(directive, graphQLDirective).forEach { graphQLArgument ->
                        argument(GraphQLAppliedDirectiveArgument.newArgument()
                            .name(graphQLArgument.name)
                            .type(graphQLArgument.type)
                            .description(graphQLArgument.description)
                            .inputValueWithState(graphQLArgument.argumentDefaultValue)
                            .build()
                        )
                    }
                }
                .build()
        }.toTypedArray()
    }

    private fun directiveProblems(directives: List<Directive>, location: Introspection.DirectiveLocation): List<String> =
        directives.groupBy { it.name }.flatMap { (name, applied) ->
            // undeclared directives have no definition to check against
            val definition = findDirective(name) ?: return@flatMap emptyList()
            buildList {
                if (location !in definition.validLocations()) {
                    add("Directive @$name${applied.first().sourceLocation.describe()} is applied to $location, but it's declared on ${definition.validLocations().joinToString(" | ")}.")
                }
                if (definition.isNonRepeatable && applied.size > 1) {
                    add("Directive @$name${applied[1].sourceLocation.describe()} isn't repeatable but is applied more than once.")
                }
                applied.forEach { directive ->
                    definition.arguments
                        .filter { it.type is GraphQLNonNull && !it.hasSetDefaultValue() && directive.getArgument(it.name) == null }
                        .forEach { add("Directive @$name${directive.sourceLocation.describe()} is missing its required argument '${it.name}'.") }
                }
            }
        }

    private fun findDirective(name: String): GraphQLDirective? = schemaDirectives[name] ?: BUILT_IN_DIRECTIVES[name]

    private fun buildUndeclaredAppliedDirective(directive: Directive): GraphQLAppliedDirective {
        return GraphQLAppliedDirective.newDirective()
            .name(directive.name)
            .definition(directive)
            .comparatorRegistry(runtimeWiring.comparatorRegistry)
            .apply {
                directive.arguments.forEach { arg ->
                    argument(GraphQLAppliedDirectiveArgument.newArgument()
                        .name(arg.name)
                        .type(guessDirectiveArgumentType(directive, arg.name, arg.value))
                        .valueLiteral(arg.value)
                        .build()
                    )
                }
            }
            .build()
    }

    // there's no directive definition to look the type up in, so it's guessed from the value
    private fun guessDirectiveArgumentType(directive: Directive, argumentName: String, value: Value<*>): GraphQLInputType {
        return when (value) {
            is StringValue, is NullValue -> Scalars.GraphQLString
            is IntValue -> Scalars.GraphQLInt
            is FloatValue -> Scalars.GraphQLFloat
            is BooleanValue -> Scalars.GraphQLBoolean
            is ArrayValue -> GraphQLList(value.values.firstOrNull()?.let { guessDirectiveArgumentType(directive, argumentName, it) } ?: Scalars.GraphQLString)
            else -> throw SchemaError("Can't guess the type of argument ${directive.name}#$argumentName of undeclared directive ${directive.name}, please declare the directive.")
        }
    }

    // TODO remove this once directives are fully replaced with applied directives
    @Suppress("DEPRECATION") // legacy directive argument values
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
                val graphQLDirective = findDirective(directive.name)
                    // graphql-java rejects legacy directives without a definition, so undeclared ones are only kept as applied directives
                    ?: if (options.allowUndeclaredDirectives) continue
                    else throw SchemaError("Found applied directive ${directive.name} without corresponding directive definition.")
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
                            missingArgumentsWithDefault(directive, graphQLDirective).forEach { graphQLArgument ->
                                val defaultValue = graphQLArgument.argumentDefaultValue
                                argument(GraphQLArgument.newArgument()
                                    .name(graphQLArgument.name)
                                    .type(graphQLArgument.type)
                                    .description(graphQLArgument.description)
                                    .apply { if (defaultValue.isLiteral) valueLiteral(defaultValue.value as Value<*>) else valueProgrammatic(defaultValue.value) }
                                    .build())
                            }
                        }
                        .build()
                )
            }
        }

        return output.toTypedArray()
    }

    private fun missingArgumentsWithDefault(directive: Directive, graphQLDirective: GraphQLDirective): List<GraphQLArgument> =
        graphQLDirective.arguments.filter { it.hasSetDefaultValue() && directive.getArgument(it.name) == null }

    private fun getSpecifiedByUrl(directives: List<Directive>): String? =
        (directives.find { it.name == Directives.SpecifiedByDirective.name }?.getArgument("url")?.value as? StringValue)?.value

    // custom scalars are in the dictionary, so they're referenced by name like the other types
    private fun scalarType(name: String?): GraphQLInputType? =
        scalarDefinitions[name]?.let { GraphQLTypeReference(it.name) } ?: GRAPHQL_SCALARS[name]

    private fun determineOutputType(typeDefinition: Type<*>) =
        determineType(GraphQLOutputType::class, typeDefinition, permittedTypesForObject) as GraphQLOutputType

    // named types other than spec scalars are type references
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
                        throw unexpectedTypeError(typeDefinition, expectedType)
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

    private fun unexpectedTypeError(typeName: TypeName, expectedType: KClass<*>): SchemaError {
        val isInput = expectedType == GraphQLInputType::class
        val definition = unusedDefinitions.find { it.name == typeName.name }
            ?: definitions.filterIsInstance<TypeDefinition<*>>().find { it.name == typeName.name }
        val outputOnlyKind = when (definition) {
            is ObjectTypeDefinition -> "an object type"
            is InterfaceTypeDefinition -> "an interface"
            is UnionTypeDefinition -> "a union"
            else -> null
        }

        val problem = when {
            definition == null -> "is not defined in the schema."
            isInput && outputOnlyKind != null ->
                "is $outputOnlyKind, which can't be used as an input type. Input types are input objects, enums and scalars."
            !isInput && definition is InputObjectTypeDefinition ->
                "is an input object type, which can't be used as an output type. Output types are object types, interfaces, unions, enums and scalars."
            definition in unusedDefinitions ->
                "is defined in the schema, but was not reached while scanning the resolvers and the classes they use, so it wasn't built. " +
                    unreachedTypeHint(definition)
            else -> "can't be used as ${if (isInput) "an input" else "an output"} type."
        }
        val usage = findUsage(typeName)?.let { " used by $it" }.orEmpty()
        return SchemaError("Type '${typeName.name}'$usage${typeName.sourceLocation.describe()} $problem")
    }

    // what makes the scanner reach the type depends on its kind, see SchemaClassScanner.canIncludeUnusedType
    private fun unreachedTypeHint(definition: TypeDefinition<*>): String = when (definition) {
        is InterfaceTypeDefinition, is UnionTypeDefinition ->
            "Return it from a resolver method or one of those classes, or set SchemaParserOptions.includeUnusedTypes."
        is EnumTypeDefinition ->
            "Use it in a resolver method or one of those classes, or set SchemaParserOptions.includeUnusedTypes and pass its enum class in the parser's dictionary."
        is InputObjectTypeDefinition ->
            "Use it in an argument of a resolver method, directly or through another input type. SchemaParserOptions.includeUnusedTypes doesn't include input types."
        is ScalarTypeDefinition ->
            "Use it in a resolver method or one of those classes. SchemaParserOptions.includeUnusedTypes doesn't include scalars."
        else ->
            "Return it from a resolver method or one of those classes, or set SchemaParserOptions.includeUnusedTypes and pass a class for it in the parser's dictionary."
    }

    // determineType only gets the type reference, so the definition using it is looked up for the error message
    private fun findUsage(typeName: TypeName): String? = (definitions + typeExtensions.values.flatten()).asSequence().flatMap { definition ->
        when (definition) {
            is ObjectTypeDefinition -> fieldUsages(definition.name, definition.fieldDefinitions)
            is InterfaceTypeDefinition -> fieldUsages(definition.name, definition.fieldDefinitions)
            is InputObjectTypeDefinition -> definition.inputValueDefinitions.map { "input field '${definition.name}.${it.name}'" to it.type }
            is DirectiveDefinition -> definition.inputValueDefinitions.map { "argument '${it.name}' of directive '@${definition.name}'" to it.type }
            else -> emptyList()
        }
    }.find { (_, type) -> type.unwrap() === typeName }?.first

    private fun fieldUsages(typeName: String, fields: List<FieldDefinition>): List<Pair<String, Type<*>>> = fields.flatMap { field ->
        listOf("field '$typeName.${field.name}'" to field.type) +
            field.inputValueDefinitions.map { "argument '${it.name}' of field '$typeName.${field.name}'" to it.type }
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

// Built from the individual constants rather than Directives.BUILT_IN_DIRECTIVES_MAP (added in graphql-java 26)
// so that the library keeps working with graphql-java 25. TODO replace with Directives.BUILT_IN_DIRECTIVES_MAP once we deploy a major version
private val BUILT_IN_DIRECTIVES = listOf(
    Directives.IncludeDirective,
    Directives.SkipDirective,
    Directives.DeprecatedDirective,
    Directives.SpecifiedByDirective,
    Directives.OneOfDirective,
    Directives.DeferDirective,
    Directives.ExperimentalDisableErrorPropagationDirective
).associateBy { it.name }

const val DEFAULT_DEPRECATION_MESSAGE = "No longer supported"

private fun SourceLocation?.describe(): String =
    this?.let { " at line $line, column $column" + sourceName?.let { name -> " in $name" }.orEmpty() }.orEmpty()
