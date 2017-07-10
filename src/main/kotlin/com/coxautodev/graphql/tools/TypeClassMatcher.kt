package com.coxautodev.graphql.tools

import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.TypeDefinition
import graphql.language.TypeName
import graphql.schema.idl.ScalarInfo
import java.lang.reflect.ParameterizedType
import java.util.Optional

/**
 * @author Andrew Potter
 */
class TypeClassMatcher(private val graphQLType: GraphQLLangType, private val javaType: JavaType, private val generic: GenericType, private val location: Location, private val definitionsByName: Map<String, TypeDefinition>) {

    private fun error(msg: String) = SchemaClassScannerError("Unable to match type definition ($graphQLType) with java type ($javaType): $msg")

    fun match() = match(graphQLType, javaType, true)

    private fun match(graphQLType: GraphQLLangType, javaType: JavaType, root: Boolean = false): Match {

        var realType = generic.unwrapGenericWrapper(javaType)
        var optional = false

        // Handle jdk8 Optionals
        if(realType is ParameterizedType && generic.isTypeAssignableFromRawClass(realType, Optional::class.java)) {
            optional = true

            if(location == Location.RETURN_TYPE && !root) {
                throw error("${Optional::class.java.name} can only be used at the top level of a return type")
            }

            realType = generic.unwrapGenericWrapper(realType.actualTypeArguments.first())

            if(realType is ParameterizedType && generic.isTypeAssignableFromRawClass(realType, Optional::class.java)) {
                throw error("${Optional::class.java.name} cannot be nested within itself")
            }
        }

        // Match graphql type to java type.
        return when(graphQLType) {
            is NonNullType -> {
                if(optional) {
                    throw error("graphql type is marked as nonnull but ${Optional::class.java.name} was used")
                }
                match(graphQLType.type, realType)
            }

            is ListType -> {
                if(realType is ParameterizedType && generic.isTypeAssignableFromRawClass(realType, List::class.java)) {
                    match(graphQLType.type, realType.actualTypeArguments.first())
                } else {
                    throw error("Java class is not a List: $realType")
                }
            }

            is TypeName -> {
                val typeDefinition = ScalarInfo.STANDARD_SCALAR_DEFINITIONS[graphQLType.name] ?: definitionsByName[graphQLType.name] ?: throw SchemaClassScannerError("No ${TypeDefinition::class.java.simpleName} for type name ${graphQLType.name}")
                Match(typeDefinition, generic.getRawClass(realType))
            }

            is TypeDefinition -> Match(graphQLType, generic.getRawClass(realType))
            else -> throw error("Unknown type: ${realType.javaClass.name}")
        }
    }

    data class Match(val type: TypeDefinition, val clazz: Class<*>)
    enum class Location {
        RETURN_TYPE,
        PARAMETER_TYPE,
    }
}
