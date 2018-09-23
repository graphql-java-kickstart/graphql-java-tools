package com.coxautodev.graphql.tools

import graphql.Scalars
import graphql.language.FieldDefinition
import graphql.language.TypeName
import graphql.schema.DataFetchingEnvironment
import org.apache.commons.lang3.ClassUtils
import org.apache.commons.lang3.reflect.FieldUtils
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable

/**
 * @author Andrew Potter
 */
internal class FieldResolverScanner(val options: SchemaParserOptions) {

    private val allowedLastArgumentTypes = listOfNotNull(DataFetchingEnvironment::class.java, options.contextClass)

    companion object {
        private val log = LoggerFactory.getLogger(FieldResolverScanner::class.java)

        fun getAllMethods(type: Class<*>) =
                (type.methods.toList() + ClassUtils.getAllSuperclasses(type).flatMap { it.methods.toList() })
                        .asSequence()
                        .filter { !it.isSynthetic }
                        .filter { !Modifier.isPrivate(it.modifiers) }
                        // discard any methods that are coming off the root of the class hierarchy
                        // to avoid issues with duplicate method declarations
                        .filter { it.declaringClass != Object::class.java }
                        .toList()
    }

    fun findFieldResolver(field: FieldDefinition, resolverInfo: ResolverInfo): FieldResolver {
        val searches = resolverInfo.getFieldSearches()

        val scanProperties = field.inputValueDefinitions.isEmpty()
        val found = searches.mapNotNull { search -> findFieldResolver(field, search, scanProperties) }

        if (resolverInfo is RootResolverInfo && found.size > 1) {
            throw FieldResolverError("Found more than one matching resolver for field '$field': $found")
        }

        return found.firstOrNull() ?: missingFieldResolver(field, searches, scanProperties)
    }

    private fun missingFieldResolver(field: FieldDefinition, searches: List<Search>, scanProperties: Boolean): FieldResolver {
        return if (options.allowUnimplementedResolvers) {
            log.warn("Missing resolver for field: $field")

            MissingFieldResolver(field, options)
        } else {
            throw FieldResolverError(getMissingFieldMessage(field, searches, scanProperties))
        }
    }

    private fun findFieldResolver(field: FieldDefinition, search: Search, scanProperties: Boolean): FieldResolver? {
        val method = findResolverMethod(field, search)
        if (method != null) {
            return MethodFieldResolver(field, search, options, method.apply { isAccessible = true })
        }

        if (scanProperties) {
            val property = findResolverProperty(field, search)
            if (property != null) {
                return PropertyFieldResolver(field, search, options, property.apply { isAccessible = true })
            }
        }

        return null
    }

    private fun isBoolean(type: GraphQLLangType) = type.unwrap().let { it is TypeName && it.name == Scalars.GraphQLBoolean.name }

    private fun findResolverMethod(field: FieldDefinition, search: Search): java.lang.reflect.Method? {

        val methods = getAllMethods(search.type)
        val argumentCount = field.inputValueDefinitions.size + if (search.requiredFirstParameterType != null) 1 else 0
        val name = field.name

        val isBoolean = isBoolean(field.type)

        // Check for the following one by one:
        //   1. Method with exact field name
        //   2. Method that returns a boolean with "is" style getter
        //   3. Method with "get" style getter
        //   4. Method with "getField" style getter
        return methods.find {
            it.name == name && verifyMethodArguments(it, argumentCount, search)
        } ?: methods.find {
            (isBoolean && it.name == "is${name.capitalize()}") && verifyMethodArguments(it, argumentCount, search)
        } ?: methods.find {
            it.name == "get${name.capitalize()}" && verifyMethodArguments(it, argumentCount, search)
        } ?: methods.find {
            it.name == "getField${name.capitalize()}" && verifyMethodArguments(it, argumentCount, search)
        }
    }

    private fun verifyMethodArguments(method: java.lang.reflect.Method, requiredCount: Int, search: Search): Boolean {
        val appropriateFirstParameter = if (search.requiredFirstParameterType != null) {
            if (MethodFieldResolver.isBatched(method, search)) {
                verifyBatchedMethodFirstArgument(method.genericParameterTypes.firstOrNull(), search.requiredFirstParameterType)
            } else {
                method.genericParameterTypes.firstOrNull()?.let {
                    it == search.requiredFirstParameterType || method.declaringClass.typeParameters.contains(it)
                } ?: false
            }
        } else {
            true
        }

        val correctParameterCount = method.parameterCount == requiredCount || (method.parameterCount == (requiredCount + 1) && allowedLastArgumentTypes.contains(method.parameterTypes.last()))
        return correctParameterCount && appropriateFirstParameter
    }

    private fun verifyBatchedMethodFirstArgument(firstType: JavaType?, requiredFirstParameterType: Class<*>?): Boolean {
        if (firstType == null) {
            return false
        }

        if (firstType !is ParameterizedType) {
            return false
        }

        if (!TypeClassMatcher.isListType(firstType, GenericType(firstType, options))) {
            return false
        }

        val typeArgument = firstType.actualTypeArguments.first() as? Class<*> ?: return false

        return typeArgument == requiredFirstParameterType
    }

    private fun findResolverProperty(field: FieldDefinition, search: Search) =
            FieldUtils.getAllFields(search.type).find { it.name == field.name }

    private fun getMissingFieldMessage(field: FieldDefinition, searches: List<Search>, scannedProperties: Boolean): String {
        val signatures = mutableListOf("")
        val isBoolean = isBoolean(field.type)

        searches.forEach { search ->
            signatures.addAll(getMissingMethodSignatures(field, search, isBoolean, scannedProperties))
        }

        val sourceName = if (field.sourceLocation != null && field.sourceLocation.sourceName != null) field.sourceLocation.sourceName else "<unknown>"
        val sourceLocation = if (field.sourceLocation != null) "$sourceName:${field.sourceLocation.line}" else "<unknown>"
        return "No method${if (scannedProperties) " or field" else ""} found as defined in schema $sourceLocation with any of the following signatures (with or without one of $allowedLastArgumentTypes as the last argument), in priority order:\n${signatures.joinToString("\n  ")}"
    }

    private fun getMissingMethodSignatures(field: FieldDefinition, search: Search, isBoolean: Boolean, scannedProperties: Boolean): List<String> {
        val baseType = search.type
        val signatures = mutableListOf<String>()
        val args = mutableListOf<String>()
        val sep = ", "

        if (search.requiredFirstParameterType != null) {
            args.add(search.requiredFirstParameterType.name)
        }

        args.addAll(field.inputValueDefinitions.map { "~${it.name}" })

        val argString = args.joinToString(sep)

        signatures.add("${baseType.name}.${field.name}($argString)")
        if (isBoolean) {
            signatures.add("${baseType.name}.is${field.name.capitalize()}($argString)")
        }
        signatures.add("${baseType.name}.get${field.name.capitalize()}($argString)")
        if (scannedProperties) {
            signatures.add("${baseType.name}.${field.name}")
        }

        return signatures
    }

    data class Search(val type: Class<*>, val resolverInfo: ResolverInfo, val source: Any?, val requiredFirstParameterType: Class<*>? = null, val allowBatched: Boolean = false)
}

class FieldResolverError(msg: String) : RuntimeException(msg)
