package com.coxautodev.graphql.tools

import com.google.common.collect.BiMap
import graphql.language.Definition
import graphql.language.FieldDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.TypeDefinition
import graphql.schema.GraphQLScalarType

/**
 * @author Andrew Potter
 */
class TypeDictionary(initialDictionary: BiMap<String, Class<*>>, allDefinitions: List<Definition>, resolvers: List<Resolver>, private val scalars: Map<String, GraphQLScalarType>) {

    private val definitionsByName = allDefinitions.filterIsInstance<TypeDefinition>().associateBy { it.name }
    private val rootResolvers = resolvers.filter { it.dataClassType == null }
    private val resolversByDataClass = resolvers.filter { it.dataClassType != null }.associateBy { it.dataClassType }

    private val dictionary = mutableMapOf<String, DictionaryEntry>()
    private val queue = linkedSetOf<QueueItem>()

    init {
        initialDictionary.forEach { (name, clazz) ->
            val definition = definitionsByName[name] ?: throw TypeDictionaryError("Class in supplied dictionary '${clazz.name}' specified type name '$name', but a type definition with that name was not found!")
            dictionary.put(definition.name, ImmutableEntry(DictionaryReference(clazz)))
        }
    }

    fun compileDictionary(queryName: String, mutationName: String, mutationRequired: Boolean) {
        val queryDefinition = definitionsByName[queryName] ?: throw TypeDictionaryError("Type definition for root query type '$queryName' not found!")
        val mutationDefinition = definitionsByName[mutationName]

        if(queryDefinition !is ObjectTypeDefinition) {
            throw TypeDictionaryError("Expected root query type's type to be ${ObjectTypeDefinition::class.java.simpleName}, but it was ${queryDefinition.javaClass.simpleName}")
        }

        if(mutationDefinition == null && mutationRequired) {
            throw TypeDictionaryError("Type definition for root mutation type '$mutationName' not found!")
        }

        val queryResolver = rootResolvers.find { it.javaClass.simpleName == queryName } ?: throw TypeDictionaryError("Root resolver for query type '$queryName' not found!")
        queue.add(QueueItem(queryResolver.javaClass, queryDefinition))

        if(mutationDefinition != null) {
            if(mutationDefinition !is ObjectTypeDefinition) {
                throw TypeDictionaryError("Expected root mutation type's type to be ${ObjectTypeDefinition::class.java.simpleName}, but it was ${mutationDefinition.javaClass.simpleName}")
            }

            val mutationResolver = rootResolvers.find { it.javaClass.simpleName == mutationName } ?: throw TypeDictionaryError("Root resolver for mutation type '$mutationName' not found!")
            queue.add(QueueItem(mutationResolver.javaClass, mutationDefinition))
        }

        while (queue.isNotEmpty()) {
            scanForDictionaryItems(queue.iterator().run { val item = next(); remove(); item })
        }
    }

    private fun scanForDictionaryItems(item: QueueItem) {
        val fields = item.type.fieldDefinitions

        fields.forEach { field ->
            val resolver = resolversByDataClass[item.clazz] ?: NoResolver(item.clazz)
            handleFieldMethod(field, resolver.getMethod(field))
        }
    }

    private fun handleFieldMethod(field: FieldDefinition, method: Resolver.GetMethodResult) {

    }

    private data class QueueItem(val clazz: Class<*>, val type: ObjectTypeDefinition)

    private interface DictionaryEntry {

    }

    private class ImmutableEntry(private val reference: Reference): DictionaryEntry {

    }

    private interface Reference

    private data class DictionaryReference(val clazz: Class<*>): Reference

    class TypeDictionaryError(message: String) : RuntimeException(message)

}

