package graphql.kickstart.tools

import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGeneratorDirectiveHelper
import graphql.schema.idl.TypeDefinitionRegistry
import kotlin.reflect.full.createInstance

private val PARAMETERS = Class.forName("graphql.schema.idl.SchemaGeneratorDirectiveHelper\$Parameters")
private val DIRECTIVE_HELPER = SchemaGeneratorDirectiveHelper::class.java

private val ON_OBJECT_METHOD = DIRECTIVE_HELPER.getMethod("onObject", GraphQLObjectType::class.java, PARAMETERS)
private val ON_INTERFACE_METHOD = DIRECTIVE_HELPER.getMethod("onInterface", GraphQLInterfaceType::class.java, PARAMETERS)
private val ON_UNION_METHOD = DIRECTIVE_HELPER.getMethod("onUnion", GraphQLUnionType::class.java, PARAMETERS)
private val ON_SCALAR_METHOD = DIRECTIVE_HELPER.getMethod("onScalar", GraphQLScalarType::class.java, PARAMETERS)
private val ON_ENUM_METHOD = DIRECTIVE_HELPER.getMethod("onEnum", GraphQLEnumType::class.java, PARAMETERS)
private val ON_INPUT_OBJECT_TYPE = DIRECTIVE_HELPER.getMethod("onInputObjectType", GraphQLInputObjectType::class.java, PARAMETERS)

/**
 * Directive behavior is used to wire up directives during schema parsing. Unfortunately, SchemaGeneratorDirectiveHelper
 * which contains the logic has package-private access to some members and must be therefore accessed via reflection.
 */
class DirectiveBehavior {

    private val directiveHelper = SchemaGeneratorDirectiveHelper::class.createInstance()

    fun onObject(element: GraphQLObjectType, params: Params): GraphQLObjectType =
            ON_OBJECT_METHOD.invoke(directiveHelper, element, params.toParameters()) as GraphQLObjectType

    fun onInterface(element: GraphQLInterfaceType, params: Params): GraphQLInterfaceType =
            ON_INTERFACE_METHOD.invoke(directiveHelper, element, params.toParameters()) as GraphQLInterfaceType

    fun onUnion(element: GraphQLUnionType, params: Params): GraphQLUnionType =
            ON_UNION_METHOD.invoke(directiveHelper, element, params.toParameters()) as GraphQLUnionType

    fun onScalar(element: GraphQLScalarType, params: Params): GraphQLScalarType =
            ON_SCALAR_METHOD.invoke(directiveHelper, element, params.toParameters()) as GraphQLScalarType

    fun onEnum(element: GraphQLEnumType, params: Params): GraphQLEnumType =
            ON_ENUM_METHOD.invoke(directiveHelper, element, params.toParameters()) as GraphQLEnumType

    fun onInputObject(element: GraphQLInputObjectType, params: Params): GraphQLInputObjectType =
            ON_INPUT_OBJECT_TYPE.invoke(directiveHelper, element, params.toParameters()) as GraphQLInputObjectType

    data class Params(val runtimeWiring: RuntimeWiring, val codeRegistryBuilder: GraphQLCodeRegistry.Builder) {
        internal fun toParameters() = PARAMETERS
                .getDeclaredConstructor(
                        TypeDefinitionRegistry::class.java,
                        RuntimeWiring::class.java,
                        Map::class.java,
                        GraphQLCodeRegistry.Builder::class.java
                ).apply { isAccessible = true }
                .newInstance(null, runtimeWiring, null, codeRegistryBuilder)
    }
}
