package scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser
import graphql.schema.*
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment

object WiringShapes : Scenario {
    override val name = "wiring-shapes"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/wiring-shapes/schema.graphqls")
        .resolvers(WiringShapesQuery())
        .directive("inspect", InspectWiring())
        .directive("required", RequiredWiring())
        .directive("described", DescribedWiring())
}

data class WiringTreeFilter(val name: String?, val children: List<WiringTreeFilter>?)

enum class State { ALIVE, GONE }

class WiringPerson(val name: String) {
    val friend: WiringPerson get() = this
    val state: State get() = State.ALIVE
    val someone: Any get() = this
}

class WiringShapesQuery : GraphQLQueryResolver {
    fun trees(filter: WiringTreeFilter?): Int = filter?.let { 1 + (it.children ?: emptyList()).sumOf { child -> trees(child) } } ?: 0

    fun person() = WiringPerson("ada")

    fun someone(): Any = WiringPerson("grace")
}

class InspectWiring : SchemaDirectiveWiring {
    override fun onInputObjectField(environment: SchemaDirectiveWiringEnvironment<GraphQLInputObjectField>): GraphQLInputObjectField {
        val type = GraphQLTypeUtil.unwrapAllAs<GraphQLNamedType>(environment.element.type)
        return environment.element.transform { it.description("sees ${type.javaClass.simpleName} ${type.name}") }
    }
}

class RequiredWiring : SchemaDirectiveWiring {
    override fun onField(environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>): GraphQLFieldDefinition =
        environment.element.transform { it.type(GraphQLNonNull.nonNull(environment.element.type)) }
}

class DescribedWiring : SchemaDirectiveWiring {
    override fun onObject(environment: SchemaDirectiveWiringEnvironment<GraphQLObjectType>): GraphQLObjectType =
        environment.element.transform { it.description("described") }

    override fun onInterface(environment: SchemaDirectiveWiringEnvironment<GraphQLInterfaceType>): GraphQLInterfaceType =
        environment.element.transform { it.description("described") }

    override fun onUnion(environment: SchemaDirectiveWiringEnvironment<GraphQLUnionType>): GraphQLUnionType =
        environment.element.transform { it.description("described") }

    override fun onEnum(environment: SchemaDirectiveWiringEnvironment<GraphQLEnumType>): GraphQLEnumType =
        environment.element.transform { it.description("described") }

    override fun onInputObjectType(environment: SchemaDirectiveWiringEnvironment<GraphQLInputObjectType>): GraphQLInputObjectType =
        environment.element.transform { it.description("described") }
}
