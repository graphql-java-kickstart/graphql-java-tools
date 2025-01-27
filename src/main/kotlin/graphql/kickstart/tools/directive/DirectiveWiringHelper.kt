package graphql.kickstart.tools.directive

import graphql.introspection.Introspection
import graphql.introspection.Introspection.DirectiveLocation.*
import graphql.kickstart.tools.SchemaParserOptions
import graphql.kickstart.tools.directive.SchemaDirectiveWiringEnvironmentImpl.Parameters
import graphql.language.DirectiveDefinition
import graphql.language.NamedNode
import graphql.language.NodeParentTree
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
        var output = wrapper.graphQlType
        // first the specific named directives
        wrapper.graphQlType.appliedDirectives.forEach { appliedDirective ->
            val env = buildEnvironment(wrapper, appliedDirective)
            val wiring = runtimeWiring.registeredDirectiveWiring[appliedDirective.name]
            wiring?.let { output = wrapper.invoker(it, env) }
        }
        // now call any statically added to the runtime
        runtimeWiring.directiveWiring.forEach { staticWiring ->
            val env = buildEnvironment(wrapper)
            output = wrapper.invoker(staticWiring, env)
        }
        // wiring factory is last (if present)
        val env = buildEnvironment(wrapper)
        if (runtimeWiring.wiringFactory.providesSchemaDirectiveWiring(env)) {
            val factoryWiring = runtimeWiring.wiringFactory.getSchemaDirectiveWiring(env)
            output = wrapper.invoker(factoryWiring, env)
        }

        return output
    }

    private fun <T : GraphQLDirectiveContainer> buildEnvironment(wrapper: WiringWrapper<T>, appliedDirective: GraphQLAppliedDirective? = null): SchemaDirectiveWiringEnvironmentImpl<T> {
        val type = wrapper.graphQlType
        val directive = appliedDirective?.let { d -> type.directives.find { it.name == d.name } }
        val nodeParentTree = buildAstTree(*listOfNotNull(
            wrapper.fieldsContainer?.definition,
            wrapper.inputFieldsContainer?.definition,
            wrapper.enumType?.definition,
            wrapper.fieldDefinition?.definition,
            type.definition
        ).filterIsInstance<NamedNode<*>>()
            .toTypedArray())
        val elementParentTree = buildRuntimeTree(*listOfNotNull(
            wrapper.fieldsContainer,
            wrapper.inputFieldsContainer,
            wrapper.enumType,
            wrapper.fieldDefinition,
            type
        ).toTypedArray())
        val params = when (type) {
            is GraphQLFieldDefinition -> schemaDirectiveParameters.newParams(type, wrapper.fieldsContainer, nodeParentTree, elementParentTree)
            is GraphQLArgument -> schemaDirectiveParameters.newParams(wrapper.fieldDefinition, wrapper.fieldsContainer, nodeParentTree, elementParentTree)
            // object or interface
            is GraphQLFieldsContainer -> schemaDirectiveParameters.newParams(type, nodeParentTree, elementParentTree)
            else -> schemaDirectiveParameters.newParams(nodeParentTree, elementParentTree)
        }
        return SchemaDirectiveWiringEnvironmentImpl(type, type.directives, type.appliedDirectives, directive, appliedDirective, params)
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
