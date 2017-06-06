package com.coxautodev.graphql.tools

import com.google.common.primitives.Primitives
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.Type
import graphql.language.TypeDefinition
import graphql.language.TypeName
import graphql.schema.idl.ScalarInfo
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future


/**
 * @author Andrew Potter
 */
class TypeClassMatcher(private val graphQLType: Type, private val javaType: JavaType, private val method: Resolver.Method, private val returnValue: Boolean, private val definitionsByName: Map<String, TypeDefinition>) {

    private fun error(msg: String) = SchemaClassScannerError("Unable to match type definition ($graphQLType) with java type ($javaType): $msg")

    fun match() = match(graphQLType, javaType, true)

    private fun match(graphQLType: Type, javaType: JavaType, root: Boolean = false): Match {

        var realType = unwrapGenericWrapper(javaType)
        var optional = false

        // Handle jdk8 Optionals
        if(realType is ParameterizedType && method.isTypeAssignableFromRawClass(realType, Optional::class.java)) {
            optional = true

            if(returnValue && !root) {
                throw error("${Optional::class.java.name} can only be used at the top level of a return type")
            }

            realType = unwrapGenericWrapper(realType.actualTypeArguments.first())

            if(realType is ParameterizedType && method.isTypeAssignableFromRawClass(realType, Optional::class.java)) {
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
                if(realType is ParameterizedType && method.isTypeAssignableFromRawClass(realType, List::class.java)) {
                    match(graphQLType.type, realType.actualTypeArguments.first())
                } else {
                    throw error("Java class is not a List: $realType")
                }
            }

            is TypeName -> {
                val typeDefinition = ScalarInfo.STANDARD_SCALAR_DEFINITIONS[graphQLType.name] ?: definitionsByName[graphQLType.name] ?: throw SchemaClassScannerError("No ${TypeDefinition::class.java.simpleName} for type name ${graphQLType.name}")
                Match(typeDefinition, method.getRawClass(realType))
            }

            is TypeDefinition -> Match(graphQLType, method.getRawClass(realType))
            else -> throw error("Unknown type: ${realType.javaClass.name}")
        }
    }

    /**
     * Unwrap certain Java types to find the "real" class.
     */
    private fun unwrapGenericWrapper(type: JavaType): JavaType {
        return when(type) {
            is ParameterizedType -> {
                return when(type.rawType) {
                    Future::class.java -> unwrapGenericWrapper(type.actualTypeArguments.first())
                    CompletionStage::class.java -> unwrapGenericWrapper(type.actualTypeArguments.first())
                    CompletableFuture::class.java -> unwrapGenericWrapper(type.actualTypeArguments.first())
                    else -> type
                }
            }
            is Class<*> -> if(type.isPrimitive) Primitives.wrap(type) else type
            is TypeVariable<*> -> method.resolveTypeVariable(type)
            else -> throw error("Unable to unwrap type: $type")
        }
    }

    data class Match(val type: TypeDefinition, val clazz: Class<*>)
}
