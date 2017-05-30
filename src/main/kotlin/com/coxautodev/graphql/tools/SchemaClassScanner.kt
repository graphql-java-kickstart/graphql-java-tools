package com.coxautodev.graphql.tools

import com.google.common.collect.BiMap
import com.google.common.collect.HashBiMap
import com.google.common.collect.Maps
import graphql.language.Definition
import graphql.language.FieldDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.SchemaDefinition
import graphql.language.Type
import graphql.language.TypeDefinition
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.ScalarInfo
import org.slf4j.LoggerFactory
import java.lang.reflect.ParameterizedType

/**
 * @author Andrew Potter
 */
class SchemaClassScanner(initialDictionary: BiMap<String, Class<*>>, private val allDefinitions: List<Definition>, private val resolvers: List<Resolver>, private val scalars: CustomScalarMap) {

    companion object {
        val log = LoggerFactory.getLogger(SchemaClassScanner::class.java)!!
    }

    private val initialDictionary = initialDictionary.mapValues { InitialDictionaryEntry(it.value) }

    private val definitionsByName = allDefinitions.filterIsInstance<TypeDefinition>().associateBy { it.name }

    private val objectDefinitions = allDefinitions.filterIsInstance<ObjectTypeDefinition>()
    private val objectDefinitionsByName = objectDefinitions.associateBy { it.name }

    private val rootResolvers = resolvers.filter { it.isRootResolver() }
    private val rootResolversByResolverClass = rootResolvers.associateBy { it.resolverType }
    private val resolversByDataClass = resolvers.filter { !it.isRootResolver() }.associateBy { it.dataClassType }

    private val dictionary = mutableMapOf<TypeDefinition, DictionaryEntry>()
    private val queue = linkedSetOf<QueueItem>()

    private val methodsByField = mutableMapOf<ObjectTypeDefinition, MutableMap<FieldDefinition, Resolver.ResolverMethod>>()

    init {
        initialDictionary.forEach { (name, clazz) ->
            if(!definitionsByName.containsKey(name)) {
                throw SchemaClassScannerError("Class in supplied dictionary '${clazz.name}' specified type name '$name', but a type definition with that name was not found!")
            }
        }
    }

    /**
     * Attempts to discover GraphQL Type -> Java Class relationships by matching return types/argument types on known fields
     */
    fun scanForClasses(): SchemaParser {

        val rootInfo = RootTypeInfo.fromSchemaDefinitions(allDefinitions.filterIsInstance<SchemaDefinition>())

        // Figure out what query and mutation types are called
        val queryName = rootInfo.getQueryName()
        val mutationName = rootInfo.getMutationName()

        val queryDefinition = definitionsByName[queryName] ?: throw SchemaClassScannerError("Type definition for root query type '$queryName' not found!")
        val mutationDefinition = definitionsByName[mutationName]

        if(queryDefinition !is ObjectTypeDefinition) {
            throw SchemaClassScannerError("Expected root query type's type to be ${ObjectTypeDefinition::class.java.simpleName}, but it was ${queryDefinition.javaClass.simpleName}")
        }

        if(mutationDefinition == null && rootInfo.isMutationRequired()) {
            throw SchemaClassScannerError("Type definition for root mutation type '$mutationName' not found!")
        }

        // Find query resolver class
        val queryResolver = rootResolvers.find { it.resolverType.simpleName == queryName } ?: throw SchemaClassScannerError("Root resolver for query type '$queryName' not found!")
        handleFoundType(queryDefinition, queryResolver.resolverType, RootResolverReference("query"))

        if(mutationDefinition != null) {
            if(mutationDefinition !is ObjectTypeDefinition) {
                throw SchemaClassScannerError("Expected root mutation type's type to be ${ObjectTypeDefinition::class.java.simpleName}, but it was ${mutationDefinition.javaClass.simpleName}")
            }

            // Find mutation resolver class (if required)
            val mutationResolver = rootResolvers.find { it.resolverType.simpleName == mutationName } ?: throw SchemaClassScannerError("Root resolver for mutation type '$mutationName' not found!")
            handleFoundType(mutationDefinition, mutationResolver.resolverType, RootResolverReference("mutation"))
        }

        // Loop over all objects scanning each one only once for more objects to discover.
        while(queue.isNotEmpty()) {
            while (queue.isNotEmpty()) {
                while (queue.isNotEmpty()) {
                    scanObjectForDictionaryItems(queue.iterator().run { val item = next(); remove(); item })
                }

                // Require all implementors of discovered interfaces to be discovered or provided.
                handleInterfaceOrUnionSubTypes(getAllObjectTypesImplementingDiscoveredInterfaces(), { "Object type '${it.name}' implements a known interface, but no class was found for that type name.  Please pass a class for type '${it.name}' in the parser's dictionary." })
            }

            // Require all members of discovered unions to be discovered.
            handleInterfaceOrUnionSubTypes(getAllObjectTypeMembersOfDiscoveredUnions(), { "Object type '${it.name}' is a member of a known union, but no class was found for that type name.  Please pass a class for type '${it.name}' in the parser's dictionary." })
        }

        return validateAndCreateParser(rootInfo)
    }

    private fun validateAndCreateParser(rootInfo: RootTypeInfo): SchemaParser {
        initialDictionary.filter { !it.value.accessed }.forEach {
            log.warn("Dictionary mapping was provided but never used, and can be safely deleted: \"${it.key}\" -> ${it.value.get().name}")
        }

        val dictionary = Maps.unmodifiableBiMap(HashBiMap.create<TypeDefinition, Class<*>>().also {
            dictionary.mapValuesTo(it) { it.value.typeClass }
        })
        val observedDefinitions = dictionary.keys.toSet()
        val scalarDefinitions = observedDefinitions.filterIsInstance<ScalarTypeDefinition>()

        // Ensure all scalar definitions have implementations and add the definition to those.
        val scalars = scalarDefinitions.filter { !ScalarInfo.STANDARD_SCALAR_DEFINITIONS.containsKey(it.name) }.map { definition ->
            val provided = scalars[definition.name] ?: throw SchemaClassScannerError("Expected a user-defined GraphQL scalar type with name '${definition.name}' but found none!")
            GraphQLScalarType(provided.name, SchemaParser.getDocumentation(definition) ?: provided.description, provided.coercing, definition)
        }.associateBy { it.name!! }

        (definitionsByName.values - observedDefinitions).forEach { definition ->
            log.warn("Schema type was defined but can never be accessed, and can be safely deleted: ${definition.name}")
        }

        (resolvers - methodsByField.flatMap { it.value.map { it.value.resolver } }.distinct()).forEach { resolver ->
            log.warn("Resolver was provided but no methods on it were used in data fetchers, and can be safely deleted: ${resolver.resolver}")
        }

        return SchemaParser(dictionary, observedDefinitions, scalars, rootInfo, methodsByField.toMap())
    }

    fun getAllObjectTypesImplementingDiscoveredInterfaces(): List<ObjectTypeDefinition> {
        return dictionary.keys.filterIsInstance<InterfaceTypeDefinition>().map { iface ->
            objectDefinitions.filter { obj -> obj.implements.filterIsInstance<TypeName>().any { it.name == iface.name } }
        }.flatten().distinct()
    }

    fun getAllObjectTypeMembersOfDiscoveredUnions(): List<ObjectTypeDefinition> {
        return dictionary.keys.filterIsInstance<UnionTypeDefinition>().map { union ->
            union.memberTypes.filterIsInstance<TypeName>().map { objectDefinitionsByName[it.name] ?: throw SchemaClassScannerError("TODO") }
        }.flatten().distinct()
    }

    fun handleInterfaceOrUnionSubTypes(types: List<ObjectTypeDefinition>, failureMessage: (ObjectTypeDefinition) -> String) {
        types.forEach { type ->
            if(!dictionary.containsKey(type)) {
                val initialEntry = initialDictionary[type.name] ?: throw SchemaClassScannerError(failureMessage(type))
                handleFoundType(type, initialEntry.get(), DictionaryReference())
            }
        }
    }

    /**
     * Scan a new object for types that haven't been mapped yet.
     */
    private fun scanObjectForDictionaryItems(item: QueueItem) {
        val fields = item.type.fieldDefinitions

        val methodMap = methodsByField.getOrPut(item.type, { mutableMapOf() })
        fields.forEach { field ->
            val resolver = rootResolversByResolverClass[item.clazz] ?: resolversByDataClass[item.clazz] ?: NoResolver(item.clazz)
            val method = resolver.getMethod(field)

            methodMap[field] = method
            handleFieldMethod(field, method)
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
            throw SchemaClassScannerError("Two different classes used for type ${type.name}:\n${realEntry.joinReferences()}\n\n- ${newEntry.typeClass}:\n|   ${reference.getDescription()}")
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
            is TypeName -> ScalarInfo.STANDARD_SCALAR_DEFINITIONS[type.name] ?: definitionsByName[type.name] ?: throw SchemaClassScannerError("No ${TypeDefinition::class.java.simpleName} for type name ${type.name}")
            is TypeDefinition -> type
            else -> throw SchemaClassScannerError("Unknown type: ${type.javaClass.name}")
        }
    }

    /**
     * Unwrap Java List type to find the "real" class.
     */
    private fun getWrappedClass(type: JavaType): Class<*> {
        return when(type) {
            is ParameterizedType -> getWrappedGenericClass(type.rawType as Class<*>, type.actualTypeArguments)
            is Class<*> -> type
            else -> throw SchemaClassScannerError("Unable to unwrap class: $type")
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

    private abstract class Reference {
        abstract fun getDescription(): String
        override fun toString() = getDescription()
    }

    private class RootResolverReference(val type: String): Reference() {
        override fun getDescription() = "root $type type"

    }

    private class DictionaryReference: Reference() {
        override fun getDescription() = "provided dictionary"
    }

    private class ReturnValueReference(private val method: Resolver.ResolverMethod): Reference() {
        override fun getDescription() = "return type of method ${method.javaMethod}"
    }

    private class MethodParameterReference(private val method: Resolver.ResolverMethod, private val index: Int): Reference() {
        override fun getDescription() = "parameter $index of method ${method.javaMethod}"
    }

    private class InitialDictionaryEntry(private val clazz: Class<*>) {
        var accessed = false
            private set

        fun get(): Class<*> {
            accessed = true
            return clazz
        }
    }
}

class SchemaClassScannerError(message: String) : RuntimeException(message)

typealias JavaType = java.lang.reflect.Type
typealias TypeClassDictionary = BiMap<TypeDefinition, Class<*>>
