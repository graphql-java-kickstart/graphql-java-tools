package com.coxautodev.graphql.tools

import org.apache.commons.lang3.reflect.TypeUtils
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

interface Generic<in T>

open class Test<in T: Collection<*>>: Generic<T>
open class Test2<D: List<Number>>: Test<D>()
open class Test3: Test2<ArrayList<Int>>()
class Test4: Test3()
class Test5<T: Number>: GraphQLResolver<T>

val foo = Test2<ArrayList<Int>>()

fun main(args: Array<String>) {
    getGenericClassHierarchy(Test4::class.java)
    getGenericClassHierarchy(Test5::class.java)
    getGenericClassHierarchy(foo.javaClass)
}

fun getGenericClassHierarchy(base: Class<*>): GenericClassHierarchy {
    val superclasses = mutableListOf<ParameterizedTypeInfo>()
    val interfaces = mutableListOf<ParameterizedTypeInfo>()

    try {
        interfaces.addAll(resolveGenericInterfaces(base, base))
    } catch (t: Throwable) {
        throw IllegalStateException("Unable to get type info of type ($base)!", t)
    }

    var type: Type? = base.genericSuperclass
    while (type != null && type != Object::class.java) {
        type = try {
            when (type) {
                is Class<*> -> {
                    superclasses.add(ParameterizedTypeInfo(type))
                    interfaces.addAll(resolveGenericInterfaces(type, base))
                    type.genericSuperclass
                }
                is ParameterizedType -> {
                    val raw = type.getRawClass()
                    superclasses.add(ParameterizedTypeInfo(raw, resolveTypeArguments(type, base)))
                    interfaces.addAll(resolveGenericInterfaces(raw, base))
                    raw.genericSuperclass
                }
                else -> throw IllegalStateException("Unknown type: ${type.javaClass}")
            }
        } catch (t: Throwable) {
            throw IllegalStateException("Unable to get type info of type ($type) as a parent of ($base)!", t)
        }
    }

    return GenericClassHierarchy(base, superclasses.toList(), interfaces.reversed().associateBy { it.raw })
}

fun resolveGenericInterfaces(type: Class<*>, base: Class<*>): List<ParameterizedTypeInfo> {
    return type.genericInterfaces.map { iface ->
        when(iface) {
            is Class<*> -> ParameterizedTypeInfo(iface)
            is ParameterizedType -> {
                ParameterizedTypeInfo(iface.getRawClass(), resolveTypeArguments(iface, base))
            }
            else -> throw IllegalStateException("Unknown type: ${iface.javaClass.name}")
        }
    }
}

fun resolveTypeArguments(type: ParameterizedType, base: Class<*>) = resolveTypeArguments(type, TypeUtils.getTypeArguments(base, type.getRawClass()) ?: mapOf())

fun resolveTypeArguments(type: ParameterizedType, variables: Map<TypeVariable<*>, Type>): ResolvedTypeArguments {
    if(type.actualTypeArguments.isEmpty()) {
        return ResolvedTypeArguments()
    }

    return resolveTypeArgumentsWithResolvedVariables(type, variables.mapValues { resolveTypeVariable(it.value, variables) })
}

fun resolveTypeVariable(type: Type, variables: Map<TypeVariable<*>, Type>): TypeArgument {
    return when(type) {
        is TypeVariable<*> -> {
            variables[type]
                ?.let { resolveTypeVariable(it, variables) }
                ?: TypeArgument.UnknownTypeVariable(type, type.bounds.map { resolveTypeVariable(it, variables) })
        }
        is Class<*> -> TypeArgument.Raw(type)
        is ParameterizedType -> TypeArgument.Parameterized(type.getRawClass(), type.actualTypeArguments.map { resolveTypeVariable(it, variables) })
        is WildcardType -> TypeArgument.Wildcard(type.upperBounds.map { resolveTypeVariable(it, variables) }, type.lowerBounds.map { resolveTypeVariable(it, variables) })
        else -> throw IllegalStateException("Unknown type: ${type.javaClass.name}")
    }
}

fun resolveTypeArgumentsWithResolvedVariables(type: ParameterizedType, resolvedVariables: Map<TypeVariable<*>, TypeArgument>): ResolvedTypeArguments {
    val arguments = type.actualTypeArguments

    val resolvedArguments = arguments.map { argument ->
        resolveTypeArgument(argument, resolvedVariables)
    }

    return ResolvedTypeArguments(resolvedArguments, resolvedVariables)
}

fun resolveTypeArgument(type: Type, variables: Map<TypeVariable<*>, TypeArgument>): TypeArgument {
    return when(type) {
        is TypeVariable<*> -> variables[type] ?: TypeArgument.UnknownTypeVariable(type, type.bounds.map { resolveTypeArgument(it, variables) })
        is Class<*> -> TypeArgument.Raw(type)
        is ParameterizedType -> TypeArgument.Parameterized(type.getRawClass(), type.actualTypeArguments.map { resolveTypeArgument(it, variables) })
        is WildcardType -> TypeArgument.Wildcard(type.upperBounds.map { resolveTypeArgument(it, variables) }, type.lowerBounds.map { resolveTypeArgument(it, variables) })
        else -> throw IllegalStateException("Unknown type: ${type.javaClass.name}")
    }
}


fun ParameterizedType.getRawClass(): Class<*> = this.rawType as? Class<*> ?: throw IllegalStateException("ParameterizedType.getRawType() did not return a Class<*>!")

class GenericClassHierarchy(val type: Class<*>, val superclasses: List<ParameterizedTypeInfo>, val interfaces: Map<Class<*>, ParameterizedTypeInfo>)
class ParameterizedTypeInfo(val raw: Class<*>, val arguments: List<TypeArgument> = listOf(), val variables: Map<TypeVariable<*>, TypeArgument> = mapOf()) {
    constructor(raw: Class<*>, resolvedTypeArguments: ResolvedTypeArguments): this(raw, resolvedTypeArguments.arguments, resolvedTypeArguments.variables)
}
data class ResolvedTypeArguments(val arguments: List<TypeArgument> = listOf(), val variables: Map<TypeVariable<*>, TypeArgument> = mapOf())

sealed class TypeArgument {
    class Raw(val type: Class<*>): TypeArgument()
    class Parameterized(val type: Class<*>, val arguments: List<TypeArgument>): TypeArgument()
    class Wildcard(val upper: List<TypeArgument>, val lower: List<TypeArgument>): TypeArgument()
    class UnknownTypeVariable(val variable: TypeVariable<*>, val bounds: List<TypeArgument>): TypeArgument()
}
