package com.coxautodev.graphql.tools

import sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl
import java.util.*

internal data class ClassEntry(val type: JavaType, val clazz: Class<out Any>, val typedArguments: Array<out Any> = arrayOf()) {
    companion object {
        fun of(clazz: Class<out Any>) = ClassEntry(clazz, clazz)

        fun of(javaType: JavaType) =
                if (javaType is ParameterizedTypeImpl) {
                    val list = arrayOf(javaType.actualTypeArguments.map { type -> type.javaClass })
                    ClassEntry(javaType, javaType.rawType, list)
                } else {
                    ClassEntry(javaType, javaType as Class<out Any>)
                }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClassEntry) return false

        if (clazz != other.clazz) return false
        if (!Arrays.equals(typedArguments, other.typedArguments)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = clazz.hashCode()
        result = 31 * result + Arrays.hashCode(typedArguments)
        return result
    }
}

