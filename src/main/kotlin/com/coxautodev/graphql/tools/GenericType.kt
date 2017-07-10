package com.coxautodev.graphql.tools

import com.google.common.primitives.Primitives
import ru.vyarus.java.generics.resolver.GenericsResolver
import sun.reflect.generics.reflectiveObjects.WildcardTypeImpl
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future

/**
 * @author Andrew Potter
 */
abstract class GenericType(val baseType: Class<*>) {

    abstract fun resolveTypeVariable(variable: TypeVariable<*>): JavaType

    fun isTypeAssignableFromRawClass(type: ParameterizedType, clazz: Class<*>): Boolean {
        return clazz.isAssignableFrom(getRawClass(type.rawType))
    }

    fun getRawClass(type: JavaType): Class<*> {
        return when(type) {
            is ParameterizedType -> getRawClass(type.rawType)
            is TypeVariable<*> -> getRawClass(resolveTypeVariable(type))
            is Class<*> -> type
            else -> throw ResolverError("Unable to unwrap base class: $type")
        }
    }

    /**
     * Unwrap certain Java types to find the "real" class.
     */
    fun unwrapGenericWrapper(type: JavaType): JavaType {
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
            is TypeVariable<*> -> resolveTypeVariable(type)
            is WildcardTypeImpl -> type.upperBounds.firstOrNull() ?: throw error("Unable to unwrap type, wildcard has no upper bound: $type")
            else -> throw error("Unable to unwrap type: $type")
        }
    }

    class GenericMethod(baseType: Class<*>, val javaMethod: Method): GenericType(baseType) {
        override fun resolveTypeVariable(variable: TypeVariable<*>): JavaType {
            return GenericsResolver.resolve(baseType).method(javaMethod).genericsInfo.getTypeGenerics(javaMethod.declaringClass)[variable.name] ?: throw ResolverError("Unable to lookup generic argument '${variable.name}' of class '$baseType' while resolving types for method: $javaMethod")
        }
    }

    class GenericClass(baseType: Class<*>): GenericType(baseType) {

        override fun resolveTypeVariable(variable: TypeVariable<*>) = resolveType(variable)

        fun resolveType(type: JavaType): JavaType {
            return GenericsResolver.resolve(baseType).resolveGenericOf(type)
        }
    }
}