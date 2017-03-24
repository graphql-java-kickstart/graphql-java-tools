package com.coxautodev.graphql.tools

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import java.lang.reflect.Method

class ResolverDataFetcher(
    val resolver: GraphQLResolver?,
    val method: Method,
    val passEnvironment: Boolean) : DataFetcher {

    companion object {
        val mapper = ObjectMapper().registerKotlinModule()

        @JvmStatic fun create(resolver: GraphQLResolver, name: String, arguments: Int): ResolverDataFetcher {
            val noMethodFound = ResolverError("No method found with name: '$name' on resolver ${resolver.javaClass.simpleName} or it's data class.")

            var method: Method? = getMethod(resolver.javaClass, name)
            if (method == null) {
                method = getMethod(resolver.graphQLResolverDataType() ?: throw noMethodFound, name) ?: throw noMethodFound
                return ResolverDataFetcher(null, method, shouldPassEnvironment(arguments, method))
            }

            return ResolverDataFetcher(resolver, method, shouldPassEnvironment(if (shouldPassSource(resolver)) arguments + 1 else arguments, method))
        }

        private fun isBoolean(method: Method):Boolean =
            method.returnType.isAssignableFrom(Boolean::class.java) ||
            method.returnType.isPrimitive && method.returnType.javaClass.name == "boolean"

        private fun getMethod(clazz: Class<*>, name: String): Method? =
            clazz.methods.find { it.name == name ||
              (isBoolean(it) && it.name == "is${name.capitalize()}") ||
              it.name == "get${name.capitalize()}"
            }

        private fun shouldPassSource(resolver: GraphQLResolver?) = resolver != null && resolver.graphQLResolverDataType() != null

        private fun shouldPassEnvironment(effectiveArgs: Int, method: Method): Boolean {
            val diff = method.parameterTypes.size - effectiveArgs

            if (diff < 0) throw ResolverError("Method '${method.name}' has too few parameters!")
            if (diff > 1) throw ResolverError("Method '${method.name}' has too many parameters!")

            return diff == 1
        }
    }

    val passSource = shouldPassSource(resolver)

    override fun get(environment: DataFetchingEnvironment): Any? {
        val args = mutableListOf<Any>()
        if (passSource) {
            val expectedType = resolver!!.graphQLResolverDataType()!!
            if (expectedType != environment.source.javaClass) {
                throw ResolverError("Source type (${environment.source.javaClass.name}) is not expected type (${expectedType.name})!")
            }
            args.add(environment.source)
        }

        args.addAll(environment.arguments.values.mapIndexed { i, arg ->
            if (arg is Map<*, *>) {
                val parameterType = method.parameterTypes[getArgumentIndex(i)]
                if (!Map::class.java.isAssignableFrom(parameterType)) {
                    return@mapIndexed mapper.convertValue(arg, parameterType)
                }
            }

            arg
        })

        if (passEnvironment) {
            args.add(environment)
        }

        return method.invoke(resolver ?: environment.source, *args.toTypedArray())
    }

    private fun getArgumentIndex(i: Int): Int = i + if (passSource) 1 else 0
}

class ResolverError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
