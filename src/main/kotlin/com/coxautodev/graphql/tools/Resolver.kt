package com.coxautodev.graphql.tools

import com.google.common.collect.BiMap
import graphql.language.FieldDefinition
import graphql.schema.DataFetchingEnvironment
import ru.vyarus.java.generics.resolver.GenericsResolver
import java.lang.reflect.Method

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

    private fun getMethod(clazz: Class<*>, name: String, argumentCount: Int): Method? {
        val methods = clazz.methods

        // Check for the following one by one:
        //   1. Method with exact field name
        //   2. Method that returns a boolean with "is" style getter
        //   3. Method with "get" style getter
        return methods.find {
            it.name == name && verifyMethodArguments(it, argumentCount)
        } ?: methods.find {
            (isBoolean(it.returnType) && it.name == "is${name.capitalize()}") && verifyMethodArguments(it, argumentCount)
        } ?: methods.find {
            it.name == "get${name.capitalize()}" && verifyMethodArguments(it, argumentCount)
        }
    }

    private fun verifyMethodArguments(method: Method, requiredCount: Int): Boolean {
        return method.parameterCount == requiredCount &&
            (method.parameterCount == (requiredCount + 1) && method.parameterTypes.last() == DataFetchingEnvironment::class.java)
    }

    fun getMethod(field: FieldDefinition): GetMethodResult {
        return getMethod(field.name, field.inputValueDefinitions.size)
    }

    open fun getMethod(name: String, argumentCount: Int): GetMethodResult {
        val method = getMethod(resolverType, name, argumentCount + 1)

        if(method != null) {
            return GetMethodResult(method, resolverType, true)
        }

        return getDataClassMethod(name, argumentCount)
    }

    protected fun getDataClassMethod(name: String, argumentCount: Int): GetMethodResult {
        if(dataClassType != null) {
            val method = getMethod(dataClassType, name, argumentCount)
            if(method != null) {
                return GetMethodResult(method, dataClassType, false)
            }
        }

        throw ResolverError(getMissingMethodMessage(name, argumentCount))
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
    data class GetMethodResult(val method: Method, val methodClass: Class<*>, val resolverMethod: Boolean)
}

class NoResolver(dataClass: Class<*>): Resolver(NoopResolver(), dataClass) {
    override fun getMethod(name: String, argumentCount: Int): GetMethodResult {
        return super.getDataClassMethod(name, argumentCount)
    }
}

