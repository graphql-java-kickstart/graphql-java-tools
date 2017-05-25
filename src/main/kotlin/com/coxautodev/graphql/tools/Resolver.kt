package com.coxautodev.graphql.tools

import com.google.common.collect.BiMap
import graphql.language.FieldDefinition
import graphql.schema.DataFetchingEnvironment
import ru.vyarus.java.generics.resolver.GenericsResolver
import java.lang.reflect.Method
import java.lang.reflect.Type

open class Resolver @JvmOverloads constructor(val resolver: GraphQLResolver<*>, dataClass: Class<*>? = null) {

    val resolverType = resolver.javaClass
    val dataClassType = dataClass ?: findDataClass()

    private fun findDataClass(): Class<*>? {
        // Grab the parent interface with type GraphQLResolver from our resolver and get its first type argument.
        val type = GenericsResolver.resolve(resolverType).type(GraphQLResolver::class.java)?.genericTypes()?.first()

        if(type == null || type !is Class<*>) {
            throw ResolverError("Unable to determine data class for resolver '${resolverType.name}' from generic interface!  This is most likely a bug with graphql-java-tools.")
        }

        return if(type != Void::class.java) type else null
    }

    private fun isBoolean(returnType: Class<*>): Boolean {
        return returnType.isAssignableFrom(Boolean::class.java) || returnType.isPrimitive && returnType.javaClass.name == "boolean"
    }

    private fun getMethod(clazz: Class<*>, name: String, argumentCount: Int, firstParameterType: Class<*>? = null): Method? {
        val methods = clazz.methods

        // Check for the following one by one:
        //   1. Method with exact field name
        //   2. Method that returns a boolean with "is" style getter
        //   3. Method with "get" style getter
        return methods.find {
            it.name == name && verifyMethodArguments(it, argumentCount, firstParameterType)
        } ?: methods.find {
            (isBoolean(it.returnType) && it.name == "is${name.capitalize()}") && verifyMethodArguments(it, argumentCount, firstParameterType)
        } ?: methods.find {
            it.name == "get${name.capitalize()}" && verifyMethodArguments(it, argumentCount, firstParameterType)
        }
    }

    private fun verifyMethodArguments(method: Method, requiredCount: Int, firstParameterType: Class<*>?): Boolean {
        val correctParameterCount = method.parameterCount == requiredCount || (method.parameterCount == (requiredCount + 1) && method.parameterTypes.last() == DataFetchingEnvironment::class.java)
        val appropriateFirstParameter = if(firstParameterType != null) method.parameterTypes.firstOrNull() == firstParameterType else true
        return correctParameterCount && appropriateFirstParameter
    }

    open fun getMethod(field: FieldDefinition): ResolverMethod {
        val method = getMethod(resolverType, field.name, field.inputValueDefinitions.size + if(dataClassType != null) 1 else 0, dataClassType)

        if(method != null) {
            return ResolverMethod(field, method, resolverType, true, dataClassType != null)
        }

        return getDataClassMethod(field)
    }

    protected fun getDataClassMethod(field: FieldDefinition): ResolverMethod {
        if(dataClassType != null) {
            val method = getMethod(dataClassType, field.name, field.inputValueDefinitions.size)
            if(method != null) {
                return ResolverMethod(field, method, dataClassType, false, false)
            }
        }

        throw ResolverError(getMissingMethodMessage(field.name, field.inputValueDefinitions.size))
    }

    fun getMissingMethodMessage(name: String, argumentCount: Int): String {
        var msg = "No method found with name: '$name' and '$argumentCount' argument${if(argumentCount == 1) "" else "s"} (+1 for resolver methods and +1 if injecting ${DataFetchingEnvironment::class.java.simpleName}) on "
        var hadResolver = false

        if(resolverType != NoopResolver::class.java) {
            msg += "resolver ${resolverType.name}"
            hadResolver = true
        }

        if(dataClassType != null) {
            if(hadResolver) {
                msg += " or its "
            }

            msg += "data class ${dataClassType.name}"
        }

        return msg
    }

    fun getName(dictionary: BiMap<String, Class<*>>): String {
        return if(dataClassType != null) dictionary.inverse()[dataClassType] ?: dataClassType.simpleName!! else resolverType.simpleName!!
    }

    protected class NoopResolver: GraphQLRootResolver

    data class ResolverMethod(val field: FieldDefinition, val javaMethod: Method, val methodClass: Class<*>, val resolverMethod: Boolean, val sourceArgument: Boolean) {

        val dataFetchingEnvironment = field.inputValueDefinitions.size == (javaMethod.parameterCount + getIndexOffset() + 1)

        private fun getIndexOffset() = if(sourceArgument) 1 else 0
        fun getJavaMethodParameterIndex(index: Int) = index + getIndexOffset()

        fun getJavaMethodParameterType(index: Int): Type? {
            val methodIndex = getJavaMethodParameterIndex(index)
            val parameters = javaMethod.parameterTypes
            if(parameters.size > methodIndex) {
                return javaMethod.genericParameterTypes[getJavaMethodParameterIndex(index)]
            } else {
                return null
            }
        }
    }
}

class NoResolver(dataClass: Class<*>): Resolver(NoopResolver(), dataClass) {
    override fun getMethod(field: FieldDefinition): ResolverMethod {
        return super.getDataClassMethod(field)
    }
}

