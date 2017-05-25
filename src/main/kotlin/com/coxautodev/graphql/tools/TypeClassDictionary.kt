package com.coxautodev.graphql.tools

import com.google.common.collect.BiMap
import graphql.language.Definition
import graphql.language.FieldDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.Type
import graphql.language.TypeDefinition
import graphql.language.TypeName
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.ScalarInfo
import java.lang.reflect.ParameterizedType

/**
 * @author Andrew Potter
 */
class TypeClassDictionary(initialDictionary: BiMap<String, Class<*>>, allDefinitions: List<Definition>, resolvers: List<Resolver>, private val scalars: Map<String, GraphQLScalarType>) {

    private val definitionsByName = allDefinitions.filterIsInstance<TypeDefinition>().associateBy { it.name }
    private val rootResolvers = resolvers.filter { it.dataClassType == null }
    private val rootResolversByResolverClass = rootResolvers.associateBy { it.resolverType }
    private val resolversByDataClass = resolvers.filter { it.dataClassType != null }.associateBy { it.dataClassType }

    private val dictionary = mutableMapOf<TypeDefinition, DictionaryEntry>()
    private val queue = linkedSetOf<QueueItem>()
    private val observedDefinitions = mutableListOf<TypeDefinition>()

    init {
        initialDictionary.forEach { (name, clazz) ->
            val definition = definitionsByName[name] ?: throw TypeClassDictionaryError("Class in supplied dictionary '${clazz.name}' specified type name '$name', but a type definition with that name was not found!")
            dictionary.put(definition, DictionaryEntry(clazz).also { it.addReference(DictionaryReference()) })
        }
    }

    /**
     * Attempts to discover GraphQL Type -> Java Class relationships by matching return types/argument types on known fields
     */
    fun compileDictionary(queryName: String, mutationName: String, mutationRequired: Boolean): TypeClassDictionary {
        val queryDefinition = definitionsByName[queryName] ?: throw TypeClassDictionaryError("Type definition for root query type '$queryName' not found!")
        val mutationDefinition = definitionsByName[mutationName]

        if(queryDefinition !is ObjectTypeDefinition) {
            throw TypeClassDictionaryError("Expected root query type's type to be ${ObjectTypeDefinition::class.java.simpleName}, but it was ${queryDefinition.javaClass.simpleName}")
        }

        if(mutationDefinition == null && mutationRequired) {
            throw TypeClassDictionaryError("Type definition for root mutation type '$mutationName' not found!")
        }

        val queryResolver = rootResolvers.find { it.resolverType.simpleName == queryName } ?: throw TypeClassDictionaryError("Root resolver for query type '$queryName' not found!")
        handleNewType(queryDefinition, queryResolver.resolverType)

        if(mutationDefinition != null) {
            if(mutationDefinition !is ObjectTypeDefinition) {
                throw TypeClassDictionaryError("Expected root mutation type's type to be ${ObjectTypeDefinition::class.java.simpleName}, but it was ${mutationDefinition.javaClass.simpleName}")
            }

            val mutationResolver = rootResolvers.find { it.resolverType.simpleName == mutationName } ?: throw TypeClassDictionaryError("Root resolver for mutation type '$mutationName' not found!")
            handleNewType(mutationDefinition, mutationResolver.resolverType)
        }

        while (queue.isNotEmpty()) {
            scanObjectForDictionaryItems(queue.iterator().run { val item = next(); remove(); item })
        }

        return this
    }

    /**
     * Scan a new object for types that haven't been mapped yet.
     */
    private fun scanObjectForDictionaryItems(item: QueueItem) {
        val fields = item.type.fieldDefinitions

        fields.forEach { field ->
            val resolver = rootResolversByResolverClass[item.clazz] ?: resolversByDataClass[item.clazz] ?: NoResolver(item.clazz)
            handleFieldMethod(field, resolver.getMethod(field))
        }
    }

    /**
     * Match types from a single field (return value and input values).
     */
    private fun handleFieldMethod(field: FieldDefinition, method: Resolver.ResolverMethod) {
        handleFoundType(getWrappedType(field.type), getWrappedClass(method.javaMethod.genericReturnType), ReturnValueReference(method))

        field.inputValueDefinitions.map { getWrappedType(it.type) }.forEachIndexed { i, type ->
            handleFoundType(type, getWrappedClass(method.getJavaMethodParameterType(i)!!), MethodParameterReference(method, i))
        }
    }

    /**
     * Enter a found type into the dictionary if it doesn't exist yet, add a reference pointing back to where it was discovered.
     */
    private fun handleFoundType(type: TypeDefinition, clazz: Class<*>, reference: Reference) {
        val newEntry = DictionaryEntry(clazz)
        val realEntry = dictionary.getOrPut(type, { newEntry })

        if(realEntry.typeClass != clazz) {
            throw TypeClassDictionaryError("Two different classes used for type ${type.name}:\n${realEntry.joinReferences()}\n\n- ${newEntry.typeClass}:\n|   ${reference.getDescription()}")
        }

        realEntry.addReference(reference)

        // Check if we just added the entry... a little odd, but it works (and thread-safe, FWIW)
        if(newEntry === realEntry) {
            handleNewType(type, clazz)
        }
    }

    /**
     * Handle a newly found type, adding it to the list of actually used types and putting it in the scanning queue if it's an object type.
     */
    private fun handleNewType(type: TypeDefinition, clazz: Class<*>) {
        observedDefinitions.add(type)

        when(type) {
            is ObjectTypeDefinition -> queue.add(QueueItem(type, clazz))
        }
    }

    /**
     * Unwrap GraphQL List and NonNull types to find the "real" type.
     */
    private fun getWrappedType(type: Type): TypeDefinition {
        return when(type) {
            is NonNullType -> getWrappedType(type.type)
            is ListType -> getWrappedType(type.type)
            is TypeName -> ScalarInfo.STANDARD_SCALAR_DEFINITIONS[type.name] ?: definitionsByName[type.name] ?: throw TypeClassDictionaryError("No ${TypeDefinition::class.java.simpleName} for type name ${type.name}")
            is TypeDefinition -> type
            else -> throw TypeClassDictionaryError("Unknown type: ${type.javaClass.name}")
        }
    }

    /**
     * Unwrap Java List type to find the "real" class.
     */
    private fun getWrappedClass(type: JavaType): Class<*> {
        return when(type) {
            is ParameterizedType -> getWrappedGenericClass(type.rawType as Class<*>, type.actualTypeArguments)
            is Class<*> -> type
            else -> throw TypeClassDictionaryError("Unable to unwrap class: $type")
        }
    }

    private fun getWrappedGenericClass(type: Class<*>, actualTypeArguments: Array<JavaType>): Class<*> {
        return when(type) {
            List::class.java -> getWrappedClass(actualTypeArguments.first())
            else -> type
        }
    }


    private data class QueueItem(val type: ObjectTypeDefinition, val clazz: Class<*>)

    private class DictionaryEntry(val typeClass: Class<*>) {
        private val references = mutableListOf<Reference>()

        fun addReference(reference: Reference) {
            references.add(reference)
        }

        fun joinReferences() = "- $typeClass:\n|   " + references.map { it.getDescription() }.joinToString("\n|   ")
    }

    private interface Reference {
        fun getDescription(): String // TODO
    }

    private class DictionaryReference: Reference {
        override fun getDescription() = "provided dictionary"
    }

    private class ReturnValueReference(private val method: Resolver.ResolverMethod): Reference {
        override fun getDescription() = "return type of method ${method.javaMethod}"
    }

    private class MethodParameterReference(private val method: Resolver.ResolverMethod, private val index: Int): Reference {
        override fun getDescription() = "parameter $index of method ${method.javaMethod}"
    }

    class TypeClassDictionaryError(message: String) : RuntimeException(message)
}

typealias JavaType = java.lang.reflect.Type

