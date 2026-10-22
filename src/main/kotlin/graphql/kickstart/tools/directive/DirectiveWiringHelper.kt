package graphql.kickstart.tools.directive

import graphql.introspection.Introspection
import graphql.introspection.Introspection.DirectiveLocation.*
import graphql.kickstart.tools.GRAPHQL_SCALARS
import graphql.kickstart.tools.directive.SchemaDirectiveWiringEnvironmentImpl.Parameters
import graphql.language.NamedNode
import graphql.language.NodeParentTree
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaDirectiveWiring
import java.util.*

internal class DirectiveWiringHelper(
    private val runtimeWiring: RuntimeWiring,
    codeRegistryBuilder: GraphQLCodeRegistry.Builder
) {
    private val schemaDirectiveParameters = Parameters(runtimeWiring, codeRegistryBuilder)

    val isWiringRequired = runtimeWiring.registeredDirectiveWiring.isNotEmpty() || runtimeWiring.directiveWiring.isNotEmpty()

    /**
     * Runs the wirings on the given types of the assembled schema. If they change any, returns every type by name with type references to
     * the others, so that the next build resolves them to the wired types.
     */
    fun wireTypes(schema: GraphQLSchema, types: List<GraphQLNamedType>): Map<String, GraphQLNamedType> {
        val changed = linkedMapOf<String, GraphQLNamedType>()
        types.forEach { type ->
            val wired = when (type) {
                is GraphQLObjectType -> wireObject(type)
                is GraphQLInterfaceType -> wireInterFace(type)
                is GraphQLInputObjectType -> wireInputObject(type)
                is GraphQLEnumType -> wireEnum(type)
                is GraphQLUnionType -> wireUnion(type)
                is GraphQLScalarType -> wireScalar(type)
                else -> type
            }
            if (wired !== type) changed[type.name] = wired
        }
        if (changed.isEmpty()) return changed
        val references = TypeReferences(schema)
        return types.associate { it.name to references.inType(changed[it.name] ?: it) }
    }

    /**
     * Swaps the schema's own types that a type refers to for type references, like they were before the schema was built.
     */
    private class TypeReferences(private val schema: GraphQLSchema) {
        // the types wirings build in code, rebuilt once each
        private val built = IdentityHashMap<GraphQLType, GraphQLType>()

        @Suppress("UNCHECKED_CAST")
        fun <T : GraphQLNamedType> inType(type: T): T = when (type) {
            is GraphQLObjectType -> type.transform { it.clearFields().fields(type.fields.map(::inField)).replaceInterfaces(of(type.interfaces)) }
            is GraphQLInterfaceType -> type.transform { it.clearFields().fields(type.fields.map(::inField)).replaceInterfacesOrReferences(of(type.interfaces)) }
            is GraphQLUnionType -> type.transform { it.replacePossibleTypes(of(type.types)) }
            is GraphQLInputObjectType -> type.transform { it.clearFields().fields(type.fields.map(::inInputField)) }
            else -> type
        } as T

        private fun inField(field: GraphQLFieldDefinition) = field.transform {
            it.type(of(field.type) as GraphQLOutputType)
                .clearArguments()
                .arguments(field.arguments.map { argument -> argument.transform { a -> a.type(of(argument.type) as GraphQLInputType) } })
        }

        private fun inInputField(field: GraphQLInputObjectField) = field.transform { it.type(of(field.type) as GraphQLInputType) }

        private fun of(type: GraphQLType): GraphQLType = when (type) {
            is GraphQLNonNull -> GraphQLNonNull.nonNull(of(type.wrappedType))
            is GraphQLList -> GraphQLList.list(of(type.wrappedType))
            is GraphQLNamedType -> when {
                // spec scalars aren't in the dictionary, so a reference only finds one another field uses
                GRAPHQL_SCALARS[type.name] === type -> type
                schema.typeMap[type.name] === type -> GraphQLTypeReference.typeRef(type.name)
                else -> built.getOrPut(type) { inType(type) }
            }
            else -> type
        }

        private fun of(types: List<GraphQLNamedOutputType>) = types.map { of(it) as GraphQLNamedOutputType }
    }

    private fun wireObject(objectType: GraphQLObjectType): GraphQLObjectType {
        return wireFields(objectType)
            .let { fields -> if (objectType.fields != fields) objectType.transform { it.clearFields().fields(fields) } else objectType }
            .let { wireDirectives(WiringWrapper(it, OBJECT, SchemaDirectiveWiring::onObject)) }
    }

    private fun wireInterFace(interfaceType: GraphQLInterfaceType): GraphQLInterfaceType {
        return wireFields(interfaceType)
            .let { fields -> if (interfaceType.fields != fields) interfaceType.transform { it.clearFields().fields(fields) } else interfaceType }
            .let { wireDirectives(WiringWrapper(it, INTERFACE, SchemaDirectiveWiring::onInterface)) }
    }

    private fun wireInputObject(inputObjectType: GraphQLInputObjectType): GraphQLInputObjectType {
        return wireInputFields(inputObjectType)
            .let { fields -> if (inputObjectType.fields != fields) inputObjectType.transform { it.clearFields().fields(fields) } else inputObjectType }
            .let { wireDirectives(WiringWrapper(it, INPUT_OBJECT, SchemaDirectiveWiring::onInputObjectType)) }
    }

    private fun wireEnum(enumType: GraphQLEnumType): GraphQLEnumType {
        return wireEnumValues(enumType)
            .let { values -> if (enumType.values != values) enumType.transform { it.clearValues().values(values) } else enumType }
            .let { wireDirectives(WiringWrapper(it, ENUM, SchemaDirectiveWiring::onEnum)) }
    }

    private fun wireUnion(unionType: GraphQLUnionType): GraphQLUnionType {
        return wireDirectives(WiringWrapper(unionType, UNION, SchemaDirectiveWiring::onUnion))
    }

    private fun wireScalar(scalarType: GraphQLScalarType): GraphQLScalarType {
        return wireDirectives(WiringWrapper(scalarType, SCALAR, SchemaDirectiveWiring::onScalar))
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
            val env = buildEnvironment(wrapper, output, appliedDirective)
            val wiring = runtimeWiring.registeredDirectiveWiring[appliedDirective.name]
            wiring?.let { output = invokeWiring(wrapper, it, env) }
        }
        // now call any statically added to the runtime
        runtimeWiring.directiveWiring.forEach { staticWiring ->
            val env = buildEnvironment(wrapper, output)
            output = invokeWiring(wrapper, staticWiring, env)
        }
        // wiring factory is last (if present)
        val env = buildEnvironment(wrapper, output)
        if (runtimeWiring.wiringFactory.providesSchemaDirectiveWiring(env)) {
            val factoryWiring = runtimeWiring.wiringFactory.getSchemaDirectiveWiring(env)
            output = invokeWiring(wrapper, factoryWiring, env)
        }

        return output
    }

    private fun <T : GraphQLDirectiveContainer> invokeWiring(wrapper: WiringWrapper<T>, wiring: SchemaDirectiveWiring, env: SchemaDirectiveWiringEnvironmentImpl<T>): T {
        return checkNotNull(wrapper.invoker(wiring, env)) { "The SchemaDirectiveWiring MUST return a non null return value for element '${wrapper.graphQlType.name}'" }
    }

    // the legacy directives still back the deprecated getters of SchemaDirectiveWiringEnvironment
    @Suppress("DEPRECATION")
    private fun <T : GraphQLDirectiveContainer> buildEnvironment(wrapper: WiringWrapper<T>, element: T, appliedDirective: GraphQLAppliedDirective? = null): SchemaDirectiveWiringEnvironmentImpl<T> {
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
        return SchemaDirectiveWiringEnvironmentImpl(element, type.directives, type.appliedDirectives, directive, appliedDirective, params)
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
        val invoker: (SchemaDirectiveWiring, SchemaDirectiveWiringEnvironmentImpl<T>) -> T?,
        val fieldsContainer: GraphQLFieldsContainer? = null,
        val fieldDefinition: GraphQLFieldDefinition? = null,
        val inputFieldsContainer: GraphQLInputFieldsContainer? = null,
        val enumType: GraphQLEnumType? = null
    )
}
