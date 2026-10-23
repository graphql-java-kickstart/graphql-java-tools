package graphql.kickstart.tools

import graphql.Directives
import graphql.Scalars
import graphql.introspection.Introspection
import graphql.introspection.Introspection.DirectiveLocation.ARGUMENT_DEFINITION
import graphql.introspection.Introspection.DirectiveLocation.SCHEMA
import graphql.kickstart.tools.util.describe
import graphql.kickstart.tools.util.getDocumentation
import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import org.slf4j.Logger

internal class SdlDirectives(
    private val directiveDefinitions: List<DirectiveDefinition>,
    private val options: SchemaParserOptions,
    private val runtimeWiring: RuntimeWiring,
    private val log: Logger,
    private val inputType: (Type<*>) -> GraphQLInputType
) {
    // by name, in the order they're declared
    private lateinit var schemaDirectives: MutableMap<String, GraphQLDirective>

    val definitions: Collection<GraphQLDirective> get() = schemaDirectives.values

    init {
        createDirectives()
    }

    fun directivesOf(definition: TypeDefinition<*>, extensions: List<TypeDefinition<*>>): List<Directive> {
        val applied = definition.directives.mapTo(mutableSetOf()) { it.name }
        return definition.directives + extensions.flatMap { extension ->
            val (repeated, kept) = extension.directives.partition { it.name in applied && isNonRepeatableByFirstDeclaration(it.name) }
            repeated.forEach { log.warn("Directive @${it.name} isn't repeatable but an extension of type '${definition.name}' applies it again, only the first one is kept.") }
            applied += kept.map { it.name }
            kept
        }
    }

    private fun isNonRepeatableByFirstDeclaration(directiveName: String): Boolean =
        directiveDefinitions.find { it.name == directiveName }?.let { !it.isRepeatable } ?: BUILT_IN_DIRECTIVES.containsKey(directiveName)

    private fun createDirectives() {
        schemaDirectives = directiveDefinitions.associateTo(linkedMapOf()) { it.name to createDirective(it) }
        // because the arguments can have directives too, we attach them only after the directives themselves are created
        schemaDirectives.replaceAll { _, d ->
            val arguments = d.arguments.map { a -> a.transform {
                it.withSdlDirectives(a.definition!!.directives, ARGUMENT_DEFINITION)
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
            .type(inputType(definition.type))
            .apply { getDeprecated(definition.directives)?.let { deprecate(it) } }
            .apply { definition.defaultValue?.let { defaultValueLiteral(it) } }
            .build()
    }

    @Suppress("DEPRECATION")
    fun <B : GraphqlDirectivesContainerTypeBuilder<B, B>> B.withSdlDirectives(directives: List<Directive>, location: Introspection.DirectiveLocation): B {
        if (directives.isEmpty()) return this
        val resolved = resolveDirectives(directives, location)
        val applied = appliedDirectives(resolved)
        val legacy = legacyDirectives(resolved, location)
        return withAppliedDirectives(*applied).withDirectives(*legacy)
    }

    fun schemaAppliedDirectives(directives: List<Directive>): List<GraphQLAppliedDirective> =
        appliedDirectives(resolveDirectives(directives, SCHEMA)).toList()

    private fun resolveDirectives(directives: List<Directive>, location: Introspection.DirectiveLocation): List<ResolvedDirective> {
        // TODO throw a SchemaError instead in 15.0.0 (#884), like graphql-java does
        directiveProblems(directives, location).forEach { log.warn(it) }
        return directives.map { directive ->
            val definition = findDirective(directive.name)
            when {
                definition != null -> ResolvedDirective(directive, declaredArguments(directive, definition), declared = true)
                options.allowUndeclaredDirectives -> ResolvedDirective(directive, undeclaredArguments(directive), declared = false)
                else -> throw SchemaError("Found applied directive ${directive.name} without corresponding directive definition.")
            }
        }
    }

    // missing arguments get their defaults like in graphql-java, which gives a bare @deprecated the "reason" SchemaPrinter needs
    private fun declaredArguments(directive: Directive, definition: GraphQLDirective): List<ResolvedArgument> {
        val supplied = directive.arguments.map { arg ->
            val argument = definition.getArgument(arg.name)
                ?: throw SchemaError("Found an unexpected directive argument ${directive.name}#${arg.name} .")
            ResolvedArgument(arg.name, argument.type, InputValueWithState.newLiteralValue(arg.value), legacyDescription = null)
        }
        val defaulted = missingArgumentsWithDefault(directive, definition).map {
            ResolvedArgument(it.name, it.type, it.argumentDefaultValue, legacyDescription = it.description)
        }
        return supplied + defaulted
    }

    private fun undeclaredArguments(directive: Directive): List<ResolvedArgument> =
        directive.arguments.map { arg ->
            ResolvedArgument(arg.name, guessDirectiveArgumentType(directive, arg.name, arg.value), InputValueWithState.newLiteralValue(arg.value), legacyDescription = null)
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

    // graphql-java's applied directives and their arguments have no description
    private fun appliedDirectives(directives: List<ResolvedDirective>): Array<GraphQLAppliedDirective> =
        directives.map { directive ->
            GraphQLAppliedDirective.newDirective()
                .name(directive.usage.name)
                .definition(directive.usage)
                .comparatorRegistry(runtimeWiring.comparatorRegistry)
                .apply {
                    directive.arguments.forEach { arg ->
                        argument(GraphQLAppliedDirectiveArgument.newArgument()
                            .name(arg.name)
                            .type(arg.type)
                            .inputValueWithState(arg.value)
                            .build()
                        )
                    }
                }
                .build()
        }.toTypedArray()

    // TODO remove this once directives are fully replaced with applied directives
    @Suppress("DEPRECATION") // legacy directive argument values
    private fun legacyDirectives(directives: List<ResolvedDirective>, location: Introspection.DirectiveLocation): Array<GraphQLDirective> {
        val seen = mutableSetOf<String>()
        return directives.mapNotNull { directive ->
            // graphql-java rejects legacy directives without a definition, so undeclared ones are only kept as applied directives
            if (!directive.declared) return@mapNotNull null
            val name = directive.usage.name
            val nonRepeatable = isNonRepeatableByFirstDeclaration(name)
            // graphql-java rejects a non-repeatable legacy directive that is applied more than once
            if (!seen.add(name) && nonRepeatable) return@mapNotNull null
            GraphQLDirective.newDirective()
                .name(name)
                .description(getDocumentation(directive.usage, options))
                .comparatorRegistry(runtimeWiring.comparatorRegistry)
                .validLocation(location)
                .repeatable(!nonRepeatable)
                .apply {
                    directive.arguments.forEach { arg ->
                        argument(GraphQLArgument.newArgument()
                            .name(arg.name)
                            .type(arg.type)
                            .description(arg.legacyDescription)
                            // there's no InputValueWithState setter. only a default can be programmatic, like @deprecated's reason
                            .apply { if (arg.value.isLiteral) valueLiteral(arg.value.value as Value<*>) else valueProgrammatic(arg.value.value) }
                            .build())
                    }
                }
                .build()
        }.toTypedArray()
    }

    private class ResolvedDirective(
        val usage: Directive,
        val arguments: List<ResolvedArgument>,
        val declared: Boolean
    )

    private class ResolvedArgument(
        val name: String,
        val type: GraphQLInputType,
        val value: InputValueWithState,
        val legacyDescription: String?
    )

    private fun missingArgumentsWithDefault(directive: Directive, graphQLDirective: GraphQLDirective): List<GraphQLArgument> =
        graphQLDirective.arguments.filter { it.hasSetDefaultValue() && directive.getArgument(it.name) == null }

    /**
     * Returns an optional [String] describing a deprecated field/enum.
     * If a deprecation directive was defined using the @deprecated directive,
     * then a String containing either the contents of the 'reason' argument, if present, or a default
     * message defined in [DEFAULT_DEPRECATION_MESSAGE] will be returned. Otherwise, `null` will be returned
     * indicating no deprecation directive was found within the directives list.
     */
    fun getDeprecated(directives: List<Directive>): String? =
        directives.find { it.name == "deprecated" }?.let { directive ->
            (directive.arguments.find { it.name == "reason" }?.value as? StringValue)?.value
                ?: DEFAULT_DEPRECATION_MESSAGE
        }
}

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
