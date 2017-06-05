package com.coxautodev.graphql.tools

import graphql.Scalars
import graphql.language.FieldDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.Type
import graphql.language.TypeName
import graphql.schema.DataFetchingEnvironment
import ru.vyarus.java.generics.resolver.GenericsResolver

open class Resolver @JvmOverloads constructor(val resolver: GraphQLResolver<*>, dataClass: Class<*>? = null) {

    val resolverType = resolver.javaClass
    val dataClassType = dataClass ?: findDataClass()

    fun getName(): String = (if(resolver is GraphQLRootResolver) resolver.resolverName else null) ?: if(isResolver()) resolverType.simpleName else dataClassType.simpleName

    private fun findDataClass(): Class<*> {
        // Grab the parent interface with type GraphQLResolver from our resolver and get its first type argument.
        val type = GenericsResolver.resolve(resolverType).type(GraphQLResolver::class.java)?.genericTypes()?.first()

        if(type == null || type !is Class<*>) {
            throw ResolverError("Unable to determine data class for resolver '${resolverType.name}' from generic interface!  This is most likely a bug with graphql-java-tools.")
        }

        return type
    }

    private fun isBoolean(type: Type): Boolean {
        return when(type) {
            is NonNullType -> isBoolean(type.type)
            is ListType -> isBoolean(type.type)
            is TypeName -> type.name == Scalars.GraphQLBoolean.name
            else -> false
        }
    }

    private fun getMethod(clazz: Class<*>, field: FieldDefinition, isResolverMethod: Boolean = false): java.lang.reflect.Method? {
        val methods = clazz.methods
        val argumentCount = field.inputValueDefinitions.size + if(isResolverMethod && !isRootResolver()) 1 else 0
        val name = field.name

        val isBoolean = isBoolean(field.type)

        // Check for the following one by one:
        //   1. Method with exact field name
        //   2. Method that returns a boolean with "is" style getter
        //   3. Method with "get" style getter
        return methods.find {
            it.name == name && verifyMethodArguments(it, argumentCount, isResolverMethod)
        } ?: methods.find {
            (isBoolean && it.name == "is${name.capitalize()}") && verifyMethodArguments(it, argumentCount, isResolverMethod)
        } ?: methods.find {
            it.name == "get${name.capitalize()}" && verifyMethodArguments(it, argumentCount, isResolverMethod)
        }
    }

    private fun verifyMethodArguments(method: java.lang.reflect.Method, requiredCount: Int, isResolverMethod: Boolean): Boolean {
        val correctParameterCount = method.parameterCount == requiredCount || (method.parameterCount == (requiredCount + 1) && method.parameterTypes.last() == DataFetchingEnvironment::class.java)
        val appropriateFirstParameter = if(isResolverMethod && !isRootResolver()) method.parameterTypes.firstOrNull() == dataClassType else true
        return correctParameterCount && appropriateFirstParameter
    }

    open fun getMethod(field: FieldDefinition): Method {
        val method = getMethod(resolverType, field, true)

        if(method != null) {
            return Method(this, field, method, resolverType, true, !isRootResolver())
        }

        return getDataClassMethod(field)
    }

    protected fun getDataClassMethod(field: FieldDefinition): Method {
        if(!isRootResolver()) {
            val method = getMethod(dataClassType, field)
            if(method != null) {
                return Method(this, field, method, dataClassType, false, false)
            }
        }

        throw ResolverError(getMissingMethodMessage(field))
    }

    fun getMissingMethodMessage(field: FieldDefinition): String {
        val signatures = mutableListOf<String>()
        val isBoolean = isBoolean(field.type)
        val sep = "\n  "

        if(isResolver()) {
            signatures.addAll(getMissingMethodSignatures(resolverType, field, isBoolean, true))
        }

        if(!isRootResolver()) {
            signatures.addAll(getMissingMethodSignatures(dataClassType, field, isBoolean, false))
        }

        return "No method found with any of the following signatures (in priority order):$sep${signatures.joinToString(sep)}"
    }

    fun getMissingMethodSignatures(baseType: Class<*>, field: FieldDefinition, isBoolean: Boolean, isResolver: Boolean): List<String> {
        val signatures = mutableListOf<String>()
        val args = mutableListOf<String>()
        val sep = ", "

        if(isResolver && !isRootResolver()) {
            args.add(dataClassType.name)
        }

        args.addAll(field.inputValueDefinitions.map { "~${it.name}" })

        val argString = args.joinToString(sep) + " [, ${DataFetchingEnvironment::class.java.name}]"

        signatures.add("${baseType.name}.${field.name}($argString)")
        if(isBoolean) {
            signatures.add("${baseType.name}.is${field.name.capitalize()}($argString)")
        }
        signatures.add("${baseType.name}.get${field.name.capitalize()}($argString)")

        return signatures
    }

    fun isResolver() = resolverType != NoopResolver::class.java
    fun isRootResolver() = dataClassType == Void::class.java

    protected class NoopResolver: GraphQLRootResolver

    class Method(val resolver: Resolver, val field: FieldDefinition, val javaMethod: java.lang.reflect.Method, val methodClass: Class<*>, val resolverMethod: Boolean, val sourceArgument: Boolean) {

        val dataFetchingEnvironment = javaMethod.parameterCount == (field.inputValueDefinitions.size + getIndexOffset() + 1)

        private fun getIndexOffset() = if(sourceArgument) 1 else 0
        fun getJavaMethodParameterIndex(index: Int) = index + getIndexOffset()

        fun getJavaMethodParameterType(index: Int): JavaType? {
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
    override fun getMethod(field: FieldDefinition): Method {
        return super.getDataClassMethod(field)
    }
}

