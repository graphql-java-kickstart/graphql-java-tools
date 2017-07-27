package com.coxautodev.graphql.tools

import graphql.Scalars
import graphql.language.FieldDefinition
import graphql.language.TypeName
import graphql.schema.DataFetchingEnvironment

/**
 * @author Andrew Potter
 */
internal class FieldResolverScanner {

    fun findFieldResolver(field: FieldDefinition, resolverInfo: ResolverInfo): FieldResolver {
        val searches = resolverInfo.getFieldSearches()

        for(search in searches) {
            val resolver = findFieldResolver(field, resolverInfo, search)
            if(resolver != null) {
                return resolver
            }
        }

        throw FieldFinderError(getMissingFieldMessage(field, searches))
    }

    private fun findFieldResolver(field: FieldDefinition, resolverInfo: ResolverInfo, search: Search): FieldResolver? {
        val method = findResolverMethod(field, search)
        if(method != null) {
            return MethodFieldResolver(field, resolverInfo, search, method)
        }

        val property = findResolverProperty(field, search)
        if(property != null) {
            return PropertyFieldResolver(field, resolverInfo, search)
        }

        return null
    }

    private fun isBoolean(type: GraphQLLangType) = type.unwrap().let { it is TypeName && it.name == Scalars.GraphQLBoolean.name }

    private fun findResolverMethod(field: FieldDefinition, search: Search): java.lang.reflect.Method? {

        val methods = search.type.methods
        val argumentCount = field.inputValueDefinitions.size + if(search.isNonRootResolver()) 1 else 0
        val name = field.name

        val isBoolean = isBoolean(field.type)

        // Check for the following one by one:
        //   1. Method with exact field name
        //   2. Method that returns a boolean with "is" style getter
        //   3. Method with "get" style getter
        return methods.find {
            it.name == name && verifyMethodArguments(it, argumentCount, search)
        } ?: methods.find {
            (isBoolean && it.name == "is${name.capitalize()}") && verifyMethodArguments(it, argumentCount, search)
        } ?: methods.find {
            it.name == "get${name.capitalize()}" && verifyMethodArguments(it, argumentCount, search)
        }
    }

    private fun verifyMethodArguments(method: java.lang.reflect.Method, requiredCount: Int, search: Search): Boolean {
        val correctParameterCount = method.parameterCount == requiredCount || (method.parameterCount == (requiredCount + 1) && method.parameterTypes.last() == DataFetchingEnvironment::class.java)
        val appropriateFirstParameter = if(search.requiresSpecificFirstParameter()) method.parameterTypes.firstOrNull() == search.requiredFirstParameterType else true
        return correctParameterCount && appropriateFirstParameter
    }


    private fun findResolverProperty(field: FieldDefinition, search: Search): PropertyFieldResolver? {
        // TODO
        return null
    }

    fun getMissingFieldMessage(field: FieldDefinition, searches: List<Search>): String {
        val signatures = mutableListOf("")
        val isBoolean = isBoolean(field.type)

        searches.forEach { search ->
            signatures.addAll(getMissingMethodSignatures(field, search, isBoolean, search.requiredFirstParameterType))
        }

        return "No method found with any of the following signatures (in priority order):${signatures.joinToString("\n  ")}"
    }


    fun getMissingMethodSignatures(field: FieldDefinition, search: Search, isBoolean: Boolean, requiredFirstParameterType: Class<*>? = null): List<String> {
        val baseType = search.type
        val signatures = mutableListOf<String>()
        val args = mutableListOf<String>()
        val sep = ", "

        if(requiredFirstParameterType != null) {
            args.add(search.requiredFirstParameterType!!.name)
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

    data class Search(val type: Class<*>, val isResolver: Boolean, val isRootResolver: Boolean, val requiredFirstParameterType: Class<*>? = null) {
        fun isNonRootResolver() = isResolver && !isRootResolver
        fun requiresSpecificFirstParameter() = isNonRootResolver() && requiredFirstParameterType != null
    }
}

class FieldFinderError(msg: String): RuntimeException(msg)
