package graphql.kickstart.tools.directive

import graphql.language.NamedNode
import graphql.language.NodeParentTree
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.util.FpKit

class SchemaDirectiveWiringEnvironmentImpl<T : GraphQLDirectiveContainer?>(
    private val element: T,
    directives: List<GraphQLDirective>,
    appliedDirectives: List<GraphQLAppliedDirective>,
    private val registeredDirective: GraphQLDirective?,
    parameters: Parameters
) : SchemaDirectiveWiringEnvironment<T> {
    private val directives: Map<String, GraphQLDirective>
    private val appliedDirectives: Map<String, GraphQLAppliedDirective>
    private val nodeParentTree: NodeParentTree<NamedNode<*>>?
    private val typeDefinitionRegistry: TypeDefinitionRegistry?
    private val context: Map<String, Any>?
    private val codeRegistry: GraphQLCodeRegistry.Builder
    private val elementParentTree: GraphqlElementParentTree?
    private val fieldsContainer: GraphQLFieldsContainer?
    private val fieldDefinition: GraphQLFieldDefinition?

    init {
        typeDefinitionRegistry = parameters.typeRegistry
        this.directives = FpKit.getByName(directives) { obj: GraphQLDirective -> obj.name }
        this.appliedDirectives = FpKit.getByName(appliedDirectives) { obj: GraphQLAppliedDirective -> obj.name }
        context = parameters.context
        codeRegistry = parameters.codeRegistry
        nodeParentTree = parameters.nodeParentTree
        elementParentTree = parameters.elementParentTree
        fieldsContainer = parameters.fieldsContainer
        fieldDefinition = parameters.fieldsDefinition
    }

    override fun getElement(): T = element
    override fun getDirective(): GraphQLDirective? = registeredDirective
    override fun getAppliedDirective(): GraphQLAppliedDirective? = appliedDirectives[registeredDirective?.name]
    override fun getDirectives(): Map<String, GraphQLDirective> = LinkedHashMap(directives)
    override fun getDirective(directiveName: String): GraphQLDirective = directives[directiveName]!!
    override fun getAppliedDirectives(): Map<String, GraphQLAppliedDirective> = appliedDirectives
    override fun getAppliedDirective(directiveName: String): GraphQLAppliedDirective = appliedDirectives[directiveName]!!
    override fun containsDirective(directiveName: String): Boolean = directives.containsKey(directiveName)
    override fun getNodeParentTree(): NodeParentTree<NamedNode<*>>? = nodeParentTree
    override fun getRegistry(): TypeDefinitionRegistry? = typeDefinitionRegistry
    override fun getBuildContext(): Map<String, Any>? = context
    override fun getCodeRegistry(): GraphQLCodeRegistry.Builder = codeRegistry
    override fun getFieldsContainer(): GraphQLFieldsContainer? = fieldsContainer
    override fun getElementParentTree(): GraphqlElementParentTree? = elementParentTree
    override fun getFieldDefinition(): GraphQLFieldDefinition? = fieldDefinition

    override fun getFieldDataFetcher(): DataFetcher<*> {
        checkNotNull(fieldDefinition) { "An output field must be in context to call this method" }
        checkNotNull(fieldsContainer) { "An output field container must be in context to call this method" }
        return codeRegistry.getDataFetcher(fieldsContainer, fieldDefinition)
    }

    override fun setFieldDataFetcher(newDataFetcher: DataFetcher<*>?): GraphQLFieldDefinition {
        checkNotNull(fieldDefinition) { "An output field must be in context to call this method" }
        checkNotNull(fieldsContainer) { "An output field container must be in context to call this method" }
        val coordinates = FieldCoordinates.coordinates(fieldsContainer, fieldDefinition)
        codeRegistry.dataFetcher(coordinates, newDataFetcher)
        return fieldDefinition
    }

    data class Parameters @JvmOverloads constructor(
        val runtimeWiring: RuntimeWiring,
        val codeRegistry: GraphQLCodeRegistry.Builder,
        val typeRegistry: TypeDefinitionRegistry? = null,
        val context: Map<String, Any>? = null,
        val nodeParentTree: NodeParentTree<NamedNode<*>>? = null,
        val elementParentTree: GraphqlElementParentTree? = null,
        val fieldsContainer: GraphQLFieldsContainer? = null,
        val fieldsDefinition: GraphQLFieldDefinition? = null
    ) {
        fun newParams(fieldsContainer: GraphQLFieldsContainer, nodeParentTree: NodeParentTree<NamedNode<*>>, elementParentTree: GraphqlElementParentTree): Parameters =
            Parameters(runtimeWiring, codeRegistry, typeRegistry, context, nodeParentTree, elementParentTree, fieldsContainer, fieldsDefinition)

        fun newParams(fieldDefinition: GraphQLFieldDefinition?, fieldsContainer: GraphQLFieldsContainer?, nodeParentTree: NodeParentTree<NamedNode<*>>, elementParentTree: GraphqlElementParentTree): Parameters =
            Parameters(runtimeWiring, codeRegistry, typeRegistry, context, nodeParentTree, elementParentTree, fieldsContainer, fieldDefinition)

        fun newParams(nodeParentTree: NodeParentTree<NamedNode<*>>, elementParentTree: GraphqlElementParentTree): Parameters =
            Parameters(runtimeWiring, codeRegistry, typeRegistry, context, nodeParentTree, elementParentTree, fieldsContainer, fieldsDefinition)
    }
}

