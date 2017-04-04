package com.coxautodev.graphql.tools

import com.google.common.collect.BiMap
import ru.vyarus.java.generics.resolver.GenericsResolver
import java.lang.reflect.Method

open class Resolver @JvmOverloads constructor(val resolver: GraphQLResolver<*>, dictionary: BiMap<String, Class<*>>, dataClass: Class<*>? = null) {

    val resolverType = resolver.javaClass
    val dataClassType = dataClass ?: findDataClass()
    val name = if(dataClassType != null) dictionary.inverse()[dataClassType] ?: dataClassType.simpleName!! else resolverType.simpleName!!

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

    private fun getMethod(clazz: Class<*>, name: String): Method? {
        val methods = clazz.methods

        // Check for the following one by one:
        //   1. Method with exact field name
        //   2. Method that returns a boolean with "is" style getter
        //   3. Method with "get" style getter
        return methods.find {
            it.name == name
        } ?: methods.find {
            (isBoolean(it.returnType) && it.name == "is${name.capitalize()}")
        } ?: methods.find {
            it.name == "get${name.capitalize()}"
        }
    }

    open fun getMethod(name: String): GetMethodResult {
        val method = getMethod(resolverType, name)

        if(method != null) {
            return GetMethodResult(method, resolverType, true)
        }

        return getDataClassMethod(name)
    }

    protected fun getDataClassMethod(name: String): GetMethodResult {
        if(dataClassType != null) {
            val method = getMethod(dataClassType, name)
            if(method != null) {
                return GetMethodResult(method, dataClassType, false)
            }
        }

        throw ResolverError(getMissingMethodMessage(name))
    }

    fun getMissingMethodMessage(name: String): String {
        var msg = "No method found with name: '$name' on "
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

    protected class NoopResolver: GraphQLRootResolver
    data class GetMethodResult(val method: Method, val methodClass: Class<*>, val resolverMethod: Boolean)
}

class NoResolver(dataClass: Class<*>, dictionary: BiMap<String, Class<*>>): Resolver(NoopResolver(), dictionary, dataClass) {
    override fun getMethod(name: String): GetMethodResult {
        return super.getDataClassMethod(name)
    }
}

