package graphql.kickstart.tools

import com.fasterxml.jackson.databind.JsonNode
import graphql.kickstart.tools.SchemaClassScanner.InputObjectReference
import graphql.kickstart.tools.SchemaClassScanner.InterfaceReference
import graphql.kickstart.tools.SchemaClassScanner.Reference
import graphql.kickstart.tools.SchemaClassScanner.ReturnValueReference
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.unwrap
import graphql.kickstart.tools.util.withExtensions
import graphql.language.*
import graphql.schema.idl.ScalarInfo
import org.slf4j.Logger
import java.lang.reflect.Member

internal class ScanDictionary(
    private val options: SchemaParserOptions,
    private val log: Logger,
    private val typeExtensions: TypeExtensions,
    private val definitionsByName: Map<String, TypeDefinition<*>>,
    private val typeClassMatcher: TypeClassMatcher,
    private val initialDictionary: Map<String, InitialDictionaryEntry>,
    private val enqueue: (ObjectTypeDefinition, JavaType) -> Unit,
    private val handleFoundScalarType: (ScalarTypeDefinition) -> Unit,
    private val dictionary: MutableMap<TypeDefinition<*>, DictionaryEntry> = mutableMapOf(),
) : Map<TypeDefinition<*>, ScanDictionary.EntryView> by dictionary {
    private val interfaceDefinitionsByName = definitionsByName.values.filterIsInstance<InterfaceTypeDefinition>().associateBy { it.name }
    private val enumsWithoutClass = mutableMapOf<EnumTypeDefinition, String>()

    fun handleFoundType(match: TypeClassMatcher.Match) = when (match) {
        is TypeClassMatcher.ScalarMatch -> handleFoundScalarType(match.type)
        is TypeClassMatcher.ValidMatch -> handleFoundType(match.type, match.javaType, match.reference)
    }

    /**
     * Enter a found type into the dictionary if it doesn't exist yet, add a reference pointing back to where it was discovered.
     */
    fun handleFoundType(type: TypeDefinition<*>, javaType: JavaType?, reference: Reference) {
        val realEntry = dictionary.getOrPut(type) { DictionaryEntry() }
        var typeWasSet = false

        if (javaType != null) {
            typeWasSet = realEntry.setTypeIfMissing(javaType)

            if (realEntry.javaType != javaType) {
                if (options.preferGraphQLResolver && realEntry.hasResolverRef()) {
                    log.warn("The real entry ${realEntry.joinReferences()} is a GraphQLResolver so ignoring this one ${javaType.unwrap()} $reference")
                } else {
                    if (Map::class.java.isAssignableFrom(javaType.unwrap())) {
                        throw SchemaClassScannerError("Two different property map classes used for type ${type.name}:\n${realEntry.joinReferences()}\n\n- ${javaType}:\n|   ${reference.getDescription()}")
                    }
                    throw SchemaClassScannerError("Two different classes used for type ${type.name}:\n${realEntry.joinReferences()}\n\n- ${javaType.typeName}:\n|   ${reference.getDescription()}")
                }
            }
        }

        realEntry.addReference(reference)

        // Check if we just added the entry... a little odd, but it works (and thread-safe, FWIW)
        if (typeWasSet && javaType != null) {
            handleNewType(type, javaType)
        }
    }

    /**
     * Handle a newly found type, adding it to the list of actually used types and putting it in the scanning queue if it's an object type.
     */
    private fun handleNewType(graphQLType: TypeDefinition<*>, javaType: JavaType) {
        when (graphQLType) {
            is ObjectTypeDefinition -> {
                enqueue(graphQLType, javaType)
                scanInterfacesOfType(graphQLType)
            }

            is InputObjectTypeDefinition -> handleInputObjectFields(graphQLType, javaType)
        }
    }

    /**
     * Finds the types of the input object's fields through the properties of its class. A null [javaType] means the input object
     * has no known class, e.g. because it's only reachable through a Map.
     */
    private fun handleInputObjectFields(graphQLType: InputObjectTypeDefinition, javaType: JavaType?) {
        val clazz = javaType?.unwrap()
        graphQLType.withExtensions(typeExtensions)
            .flatMap { it.inputValueDefinitions }
            .forEach { inputValueDefinition ->
                val inputGraphQLType = inputValueDefinition.type.unwrap()
                if (ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.containsKey(inputGraphQLType.name)) {
                    return@forEach
                }

                val propertyType = clazz?.let { findInputValueTypeInType(inputValueDefinition.name, it) }
                val inputValueJavaType = propertyType ?: initialDictionary[inputGraphQLType.name]?.get()
                if (inputValueJavaType == null) {
                    handleInputValueWithoutJavaType(graphQLType, inputValueDefinition, inputGraphQLType, clazz)
                    return@forEach
                }

                handleFoundType(typeClassMatcher.match(TypeClassMatcher.PotentialMatch.parameterType(
                    // a class from the dictionary is for the unwrapped type, not for its list or non-null wrapper
                    if (propertyType != null) inputValueDefinition.type else inputGraphQLType,
                    inputValueJavaType,
                    GenericTypeResolver(javaType ?: inputValueJavaType, options),
                    InputObjectReference(inputValueDefinition)
                )))
            }
    }

    /**
     * Without a Java property for an input field its type can still be built, as long as it doesn't need a class of its own.
     * At runtime the value is whatever the input object's class (or the Map) deserializes it into.
     */
    private fun handleInputValueWithoutJavaType(graphQLType: InputObjectTypeDefinition, inputValueDefinition: InputValueDefinition, inputGraphQLType: TypeName, clazz: Class<*>?) {
        when (val definition = definitionsByName[inputGraphQLType.name]) {
            is ScalarTypeDefinition -> handleFoundScalarType(definition)
            is InputObjectTypeDefinition -> {
                val isNewType = !dictionary.containsKey(definition)
                // registered without a class, so a class found for it later still gets set and scanned
                handleFoundType(definition, null, InputObjectReference(inputValueDefinition))
                if (isNewType) {
                    handleInputObjectFields(definition, null)
                }
            }
            // the enum class may still be found through another field, so this is only reported once scanning is done
            is EnumTypeDefinition -> enumsWithoutClass.putIfAbsent(definition, enumWithoutClassMessage(definition, graphQLType, inputValueDefinition, clazz))
            // undefined types and output types are reported by the SchemaParser
            else -> {}
        }
    }

    // an input bound to Any gets a Map at runtime too
    private fun isPropertyMap(clazz: Class<*>) =
        Map::class.java.isAssignableFrom(clazz) || JsonNode::class.java.isAssignableFrom(clazz) || clazz == Any::class.java

    private fun isLibraryClass(clazz: Class<*>) =
        listOf("java.", "kotlin.", "com.fasterxml.jackson.").any { clazz.name.startsWith(it) }

    private fun enumWithoutClassMessage(enumType: EnumTypeDefinition, graphQLType: InputObjectTypeDefinition, inputValueDefinition: InputValueDefinition, clazz: Class<*>?): String {
        val field = inputValueDefinition.name
        val reason = when {
            clazz == null -> "no class was found for input type '${graphQLType.name}'"
            isPropertyMap(clazz) && isLibraryClass(clazz) -> "'${graphQLType.name}' is bound to ${clazz.name}, which has no type information for its fields"
            else -> "${clazz.name} has no public getter or field named '$field'"
        }
        return "Enum type '${enumType.name}' is used by field '$field' of input type '${graphQLType.name}', but its Java enum can't be determined because $reason. " +
            "Pass the enum class for type '${enumType.name}' in the parser's dictionary, or use a class for '${graphQLType.name}' with a public getter or field '$field'."
    }

    fun throwIfEnumWithoutClass() {
        enumsWithoutClass.entries
            .find { dictionary[it.key]?.javaType == null }
            ?.let { throw SchemaClassScannerError(it.value) }
    }

    fun scanInterfacesOfType(graphQLType: ObjectTypeDefinition) {
        graphQLType.withExtensions(typeExtensions).flatMap { it.implements }.forEach {
            if (it is TypeName) {
                handleFoundType(interfaceDefinitionsByName[it.name]
                    ?: throw SchemaClassScannerError("Object type ${graphQLType.name} declared interface ${it.name}, but no interface with that name was found in the schema!"), null, InterfaceReference(graphQLType))
            }
        }
    }

    private fun findInputValueTypeInType(name: String, clazz: Class<*>): JavaType? {
        // a Map or JsonNode has no properties for the input's fields, only methods that may share their names, e.g. ObjectNode.fields(),
        // but a subclass of one may still declare typed getters or fields of its own
        val isProperty = { member: Member -> !isPropertyMap(clazz) || !isLibraryClass(member.declaringClass) }
        val methods = clazz.methods.filter(isProperty)

        val (getters, methodsWithParameters) = methods.filter {
            it.name == name || it.name == "get${name.replaceFirstChar(Char::titlecase)}"
        }.sortedBy { it.name.length }.partition { it.parameterCount == 0 }

        return getters.find { !it.isSynthetic }?.genericReturnType
            ?: getters.firstOrNull()?.genericReturnType
            ?: clazz.fields.find { it.name == name && isProperty(it) }?.genericType
            ?: methodsWithParameters.find { !it.isSynthetic }?.genericReturnType
            ?: methodsWithParameters.firstOrNull()?.genericReturnType
    }

    interface EntryView {
        val javaType: JavaType?
        fun typeClass(): Class<out Any>?
    }

    class DictionaryEntry : EntryView {
        private val references = mutableListOf<Reference>()
        override var javaType: JavaType? = null
            private set

        fun setTypeIfMissing(javaType: JavaType): Boolean {
            if (this.javaType == null) {
                this.javaType = javaType
                return true
            }

            return false
        }

//        fun type(): JavaType? = classEntry?.type

        override fun typeClass(): Class<out Any>? = javaType?.unwrap()

        fun addReference(reference: Reference) {
            references.add(reference)
        }

        fun joinReferences() = "- ${javaType?.typeName}:\n|   " + references.joinToString("\n|   ") { it.getDescription() }

        fun hasResolverRef(): Boolean {
            references.filterIsInstance<ReturnValueReference>().forEach { reference ->
                if (GraphQLResolver::class.java.isAssignableFrom(reference.getMethod().declaringClass)) {
                    return true
                }
            }
            return false
        }
    }

    class InitialDictionaryEntry(private val clazz: Class<*>) {
        var accessed = false
            private set

        fun get(): Class<*> {
            accessed = true
            return clazz
        }
    }
}
