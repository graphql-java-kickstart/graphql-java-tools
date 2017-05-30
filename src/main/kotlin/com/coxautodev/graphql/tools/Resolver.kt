package com.coxautodev.graphql.tools

import graphql.language.FieldDefinition
import graphql.schema.DataFetchingEnvironment
import ru.vyarus.java.generics.resolver.GenericsResolver
import java.lang.reflect.Method
import java.lang.reflect.Type

open class Resolver @JvmOverloads constructor(val resolver: GraphQLResolver<*>, dataClass: Class<*>? = null) {

    val resolverType = resolver.javaClass
    val dataClassType = dataClass ?: findDataClass()

    private fun findDataClass(): Class<*> {
        // Grab the parent interface with type GraphQLResolver from our resolver and get its first type argument.
        val type = GenericsResolver.resolve(resolverType).type(GraphQLResolver::class.java)?.genericTypes()?.first()

        if(type == null || type !is Class<*>) {
            throw ResolverError("Unable to determine data class for resolver '${resolverType.name}' from generic interface!  This is most likely a bug with graphql-java-tools.")
        }

        return type
    }

    private fun isBoolean(returnType: Class<*>): Boolean {
        return returnType.isAssignableFrom(Boolean::class.java) || returnType.isPrimitive && returnType.javaClass.name == "boolean"
    }

    private fun getMethod(clazz: Class<*>, name: String, argumentCount: Int, isResolverMethod: Boolean = false): Method? {
        val methods = clazz.methods

        // Check for the following one by one:
        //   1. Method with exact field name
        //   2. Method that returns a boolean with "is" style getter
        //   3. Method with "get" style getter
        return methods.find {
            it.name == name && verifyMethodArguments(it, argumentCount, isResolverMethod)
        } ?: methods.find {
            (isBoolean(it.returnType) && it.name == "is${name.capitalize()}") && verifyMethodArguments(it, argumentCount, isResolverMethod)
        } ?: methods.find {
            it.name == "get${name.capitalize()}" && verifyMethodArguments(it, argumentCount, isResolverMethod)
        }
    }

    private fun verifyMethodArguments(method: Method, requiredCount: Int, isResolverMethod: Boolean): Boolean {
        val correctParameterCount = method.parameterCount == requiredCount || (method.parameterCount == (requiredCount + 1) && method.parameterTypes.last() == DataFetchingEnvironment::class.java)
        val appropriateFirstParameter = if(isResolverMethod && !isRootResolver()) method.parameterTypes.firstOrNull() == dataClassType else true
        return correctParameterCount && appropriateFirstParameter
    }

    open fun getMethod(field: FieldDefinition): ResolverMethod {
        val method = getMethod(resolverType, field.name, field.inputValueDefinitions.size + if(!isRootResolver()) 1 else 0, true)

        if(method != null) {
            return ResolverMethod(this, field, method, resolverType, true, !isRootResolver())
        }

        return getDataClassMethod(field)
    }

    protected fun getDataClassMethod(field: FieldDefinition): ResolverMethod {
        if(!isRootResolver()) {
            val method = getMethod(dataClassType, field.name, field.inputValueDefinitions.size)
            if(method != null) {
                return ResolverMethod(this, field, method, dataClassType, false, false)
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

        if(!isRootResolver()) {
            if(hadResolver) {
                msg += " or its "
            }

            msg += "data class ${dataClassType.name}"
        }

        return msg
    }

    fun isRootResolver() = dataClassType == Void::class.java

    protected class NoopResolver: GraphQLRootResolver

    data class ResolverMethod(val resolver: Resolver, val field: FieldDefinition, val javaMethod: Method, val methodClass: Class<*>, val resolverMethod: Boolean, val sourceArgument: Boolean) {

        val dataFetchingEnvironment = javaMethod.parameterCount == (field.inputValueDefinitions.size + getIndexOffset() + 1)

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

