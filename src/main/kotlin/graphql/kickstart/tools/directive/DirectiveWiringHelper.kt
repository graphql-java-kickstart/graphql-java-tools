package graphql.kickstart.tools.directive

import graphql.Scalars
import graphql.introspection.Introspection
import graphql.introspection.Introspection.DirectiveLocation.*
import graphql.kickstart.tools.SchemaError
import graphql.kickstart.tools.SchemaParserOptions
import graphql.kickstart.tools.directive.SchemaDirectiveWiringEnvironmentImpl.Parameters
import graphql.kickstart.tools.util.getDocumentation
import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaDirectiveWiring
import java.util.*

class DirectiveWiringHelper(
    private val options: SchemaParserOptions,
    private val runtimeWiring: RuntimeWiring,
    codeRegistryBuilder: GraphQLCodeRegistry.Builder,
    private val directiveDefinitions: List<DirectiveDefinition>
) {
    private val schemaDirectiveParameters = Parameters(runtimeWiring, codeRegistryBuilder)

    fun wireObject(objectType: GraphQLObjectType): GraphQLObjectType {
        return wireFields(objectType)
            .let { fields -> if (objectType.fields != fields) objectType.transform { it.clearFields().fields(fields) } else objectType }
            .let { wireDirectives(WiringWrapper(it, OBJECT, SchemaDirectiveWiring::onObject)) }
    }

    fun wireInterFace(interfaceType: GraphQLInterfaceType): GraphQLInterfaceType {
        return wireFields(interfaceType)
            .let { fields -> if (interfaceType.fields != fields) interfaceType.transform { it.clearFields().fields(fields) } else interfaceType }
            .let { wireDirectives(WiringWrapper(it, INTERFACE, SchemaDirectiveWiring::onInterface)) }
    }

    fun wireInputObject(inputObjectType: GraphQLInputObjectType): GraphQLInputObjectType {
        return wireInputFields(inputObjectType)
            .let { fields -> if (inputObjectType.fields != fields) inputObjectType.transform { it.clearFields().fields(fields) } else inputObjectType }
            .let { wireDirectives(WiringWrapper(it, INPUT_OBJECT, SchemaDirectiveWiring::onInputObjectType)) }
    }

    fun wireEnum(enumType: GraphQLEnumType): GraphQLEnumType {
        return wireEnumValues(enumType)
            .let { values -> if (enumType.values != values) enumType.transform { it.clearValues().values(values) } else enumType }
            .let { wireDirectives(WiringWrapper(it, ENUM, SchemaDirectiveWiring::onEnum)) }
    }

    fun wireUnion(unionType: GraphQLUnionType): GraphQLUnionType {
        return wireDirectives(WiringWrapper(unionType, UNION, SchemaDirectiveWiring::onUnion))
    }

    private fun wireFields(fieldsContainer: GraphQLFieldsContainer): List<GraphQLFieldDefinition> {
        return fieldsContainer.fields.map { field ->
            // wire arguments
            val newArguments = field.arguments.map {
                wireDirectives(WiringWrapper(it, ARGUMENT_DEFINITION, SchemaDirectiveWiring::onArgument, fieldsContainer, field))
            }

            newArguments
                .let { args -> if (field.arguments != args) field.transform { it.clearArguments().arguments(args) } else field }
                .let { wireDirectives(WiringWrapper(it, FIELD_DEFINITION, SchemaDirectiveWiring::onField, fieldsContainer)) }
        }
    }

    private fun wireInputFields(fieldsContainer: GraphQLInputFieldsContainer): List<GraphQLInputObjectField> {
        return fieldsContainer.fieldDefinitions.map { field ->
            wireDirectives(WiringWrapper(field, FIELD_DEFINITION, SchemaDirectiveWiring::onInputObjectField, inputFieldsContainer = fieldsContainer))
        }
    }

    private fun wireEnumValues(enumType: GraphQLEnumType): List<GraphQLEnumValueDefinition> {
        return enumType.values.map { value ->
            wireDirectives(WiringWrapper(value, FIELD_DEFINITION, SchemaDirectiveWiring::onEnumValue, enumType = enumType))
        }
    }

    private fun <T : GraphQLDirectiveContainer> wireDirectives(wrapper: WiringWrapper<T>): T {
        val directivesContainer = wrapper.graphQlType.definition as DirectivesContainer<*>
        val directives = buildDirectives(directivesContainer.directives, wrapper.directiveLocation)
        val directivesByName = directives.associateBy { it.name }
        var output = wrapper.graphQlType
        // first the specific named directives
        wrapper.graphQlType.appliedDirectives.forEach { appliedDirective ->
            val env = buildEnvironment(wrapper, directives, directivesByName[appliedDirective.name], appliedDirective)
            val wiring = runtimeWiring.registeredDirectiveWiring[appliedDirective.name]
            wiring?.let { output = wrapper.invoker(it, env) }
        }
        // now call any statically added to the runtime
        runtimeWiring.directiveWiring.forEach { staticWiring ->
            val env = buildEnvironment(wrapper, directives, null, null)
            output = wrapper.invoker(staticWiring, env)
        }
        // wiring factory is last (if present)
        val env = buildEnvironment(wrapper, directives, null, null)
        if (runtimeWiring.wiringFactory.providesSchemaDirectiveWiring(env)) {
            val factoryWiring = runtimeWiring.wiringFactory.getSchemaDirectiveWiring(env)
            output = wrapper.invoker(factoryWiring, env)
        }

        return output
    }

    fun buildDirectives(directives: List<Directive>, directiveLocation: Introspection.DirectiveLocation): List<GraphQLDirective> {
        val names = mutableSetOf<String>()
        val output = mutableListOf<GraphQLDirective>()

        for (directive in directives) {
            val repeatable = directiveDefinitions.find { it.name.equals(directive.name) }?.isRepeatable ?: false
            if (repeatable || !names.contains(directive.name)) {
                names.add(directive.name)
                output.add(
                    GraphQLDirective.newDirective()
                        .name(directive.name)
                        .description(getDocumentation(directive, options))
                    .comparatorRegistry(runtimeWiring.comparatorRegistry)
                    .validLocation(directiveLocation)
                    .repeatable(repeatable)
                    .apply {
                        directive.arguments.forEach { arg ->
                            argument(GraphQLArgument.newArgument()
                                .name(arg.name)
                                .type(buildDirectiveInputType(arg.value))
                                // TODO remove this once directives are fully replaced with applied directives
                                .valueLiteral(arg.value)
                                .build())
                        }
                    }
                    .build()
                )
            }
        }

        return output
    }

    private fun <T : GraphQLDirectiveContainer> buildEnvironment(wrapper: WiringWrapper<T>, directives: List<GraphQLDirective>, directive: GraphQLDirective?, appliedDirective: GraphQLAppliedDirective?): SchemaDirectiveWiringEnvironmentImpl<T> {
        val nodeParentTree = buildAstTree(*listOfNotNull(
            wrapper.fieldsContainer?.definition,
            wrapper.inputFieldsContainer?.definition,
            wrapper.enumType?.definition,
            wrapper.fieldDefinition?.definition,
            wrapper.graphQlType.definition
        ).filterIsInstance<NamedNode<*>>()
            .toTypedArray())
        val elementParentTree = buildRuntimeTree(*listOfNotNull(
            wrapper.fieldsContainer,
            wrapper.inputFieldsContainer,
            wrapper.enumType,
            wrapper.fieldDefinition,
            wrapper.graphQlType
        ).toTypedArray())
        val params = when (wrapper.graphQlType) {
            is GraphQLFieldDefinition -> schemaDirectiveParameters.newParams(wrapper.graphQlType, wrapper.fieldsContainer, nodeParentTree, elementParentTree)
            is GraphQLArgument -> schemaDirectiveParameters.newParams(wrapper.fieldDefinition, wrapper.fieldsContainer, nodeParentTree, elementParentTree)
            // object or interface
            is GraphQLFieldsContainer -> schemaDirectiveParameters.newParams(wrapper.graphQlType, nodeParentTree, elementParentTree)
            else -> schemaDirectiveParameters.newParams(nodeParentTree, elementParentTree)
        }
        return SchemaDirectiveWiringEnvironmentImpl(wrapper.graphQlType, directives, wrapper.graphQlType.appliedDirectives, directive, appliedDirective, params)
    }

    fun buildDirectiveInputType(value: Value<*>): GraphQLInputType? {
        return when (value) {
            is NullValue -> Scalars.GraphQLString
            is FloatValue -> Scalars.GraphQLFloat
            is StringValue -> Scalars.GraphQLString
            is IntValue -> Scalars.GraphQLInt
            is BooleanValue -> Scalars.GraphQLBoolean
            is ArrayValue -> GraphQLList.list(buildDirectiveInputType(getArrayValueWrappedType(value)))
            else -> throw SchemaError("Directive values of type '${value::class.simpleName}' are not supported yet.")
        }
    }

    private fun getArrayValueWrappedType(value: ArrayValue): Value<*> {
        // empty array [] is equivalent to [null]
        if (value.values.isEmpty()) {
            return NullValue.newNullValue().build()
        }

        // get rid of null values
        val nonNullValueList = value.values.filter { v -> v !is NullValue }

        // [null, null, ...] unwrapped is null
        if (nonNullValueList.isEmpty()) {
            return NullValue.newNullValue().build()
        }

        // make sure the array isn't polymorphic
        val distinctTypes = nonNullValueList
            .map { it::class.java }
            .distinct()

        if (distinctTypes.size > 1) {
            throw SchemaError("Arrays containing multiple types of values are not supported yet.")
        }

        // peek at first value, value exists and is assured to be non-null
        return nonNullValueList[0]
    }

    private fun buildAstTree(vararg nodes: NamedNode<*>): NodeParentTree<NamedNode<*>> {
        val nodeStack: Deque<NamedNode<*>> = ArrayDeque()
        nodes.forEach { node -> nodeStack.push(node) }
        return NodeParentTree(nodeStack)
    }

    private fun buildRuntimeTree(vararg elements: GraphQLSchemaElement): GraphqlElementParentTree {
        val nodeStack: Deque<GraphQLSchemaElement> = ArrayDeque()
        elements.forEach { element -> nodeStack.push(element) }
        return GraphqlElementParentTree(nodeStack)
    }

    private data class WiringWrapper<T : GraphQLDirectiveContainer>(
        val graphQlType: T,
        val directiveLocation: Introspection.DirectiveLocation,
        val invoker: (SchemaDirectiveWiring, SchemaDirectiveWiringEnvironmentImpl<T>) -> T,
        val fieldsContainer: GraphQLFieldsContainer? = null,
        val fieldDefinition: GraphQLFieldDefinition? = null,
        val inputFieldsContainer: GraphQLInputFieldsContainer? = null,
        val enumType: GraphQLEnumType? = null
    )
}
