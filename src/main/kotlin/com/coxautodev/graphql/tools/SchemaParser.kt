package com.coxautodev.graphql.tools

import com.google.common.collect.BiMap
import com.google.common.collect.HashBiMap
import graphql.Scalars.GraphQLBigDecimal
import graphql.Scalars.GraphQLBigInteger
import graphql.Scalars.GraphQLBoolean
import graphql.Scalars.GraphQLByte
import graphql.Scalars.GraphQLChar
import graphql.Scalars.GraphQLFloat
import graphql.Scalars.GraphQLID
import graphql.Scalars.GraphQLInt
import graphql.Scalars.GraphQLLong
import graphql.Scalars.GraphQLShort
import graphql.Scalars.GraphQLString
import graphql.language.AbstractNode
import graphql.language.Definition
import graphql.language.Directive
import graphql.language.Document
import graphql.language.EnumTypeDefinition
import graphql.language.FieldDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.SchemaDefinition
import graphql.language.StringValue
import graphql.language.Type
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
import graphql.parser.Parser
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLUnionType
import graphql.schema.TypeResolverProxy

/**
 * Parses a GraphQL Schema and maps object fields to provided class methods.
 *
 * @author Andrew Potter
 */
class SchemaParser private constructor(doc: Document, resolvers: List<GraphQLResolver<*>>, scalars: List<GraphQLScalarType>, private val dictionary: BiMap<String, Class<*>>) {

    class Builder(
        private val schemaString: StringBuilder = StringBuilder(),
        private val resolvers: MutableList<GraphQLResolver<*>> = mutableListOf(),
        private val dictionary: BiMap<String, Class<*>> = HashBiMap.create(),
        private val scalars: MutableList<GraphQLScalarType> = mutableListOf()) {

        /**
         * Add GraphQL schema files from the classpath.
         */
        fun files(vararg files: String) = this.apply {
            files.forEach { this.file(it) }
        }

        /**
         * Add a GraphQL Schema file from the classpath.
         */
        fun file(filename: String) = this.apply {
            this.schemaString(java.io.BufferedReader(java.io.InputStreamReader(
                object : Any() {}.javaClass.classLoader.getResourceAsStream(filename) ?: throw java.io.FileNotFoundException("classpath:$filename")
            )).readText())
        }

        /**
         * Add a GraphQL schema string directly.
         */
        fun schemaString(string: String) = this.apply {
            schemaString.append("\n").append(string)
        }

        /**
         * Add GraphQLResolvers to the parser's dictionary.
         */
        fun resolvers(vararg resolvers: GraphQLResolver<*>) = this.apply {
            this.resolvers.addAll(resolvers)
        }

        /**
         * Add GraphQLResolvers to the parser's dictionary.
         */
        fun resolvers(resolvers: List<GraphQLResolver<*>>) = this.apply {
            this.resolvers.addAll(resolvers)
        }

        /**
         * Add data classes to the parser's dictionary.
         */
        fun dataClasses(vararg dataClasses: Class<*>) = this.apply {
            this.dictionary(*dataClasses)
        }

        /**
         * Add enums to the parser's dictionary.
         */
        fun enums(vararg enums: Class<*>) = this.apply {
            this.dictionary(*enums)
        }

        /**
         * Add arbitrary classes to the parser's dictionary.
         */
        fun dictionary(name: String, clazz: Class<*>): Builder = this.apply {
            this.dictionary.put(name, clazz)
        }

        /**
         * Add arbitrary classes to the parser's dictionary.
         */
        fun dictionary(dictionary: Map<String, Class<*>>) = this.apply {
            this.dictionary.putAll(dictionary)
        }

        /**
         * Add arbitrary classes to the parser's dictionary.
         */
        fun dictionary(clazz: Class<*>) = this.apply {
            this.dictionary(clazz.simpleName, clazz)
        }

        /**
         * Add arbitrary classes to the parser's dictionary.
         */
        fun dictionary(vararg dictionary: Class<*>) = this.apply {
            dictionary.forEach { this.dictionary(it) }
        }

        /**
         * Add arbitrary classes to the parser's dictionary.
         */
        fun dictionary(dictionary: List<Class<*>>) = this.apply {
            dictionary.forEach { this.dictionary(it) }
        }

        /**
         * Add scalars to the parser's dictionary.
         */
        fun scalars(vararg scalars: GraphQLScalarType) = this.apply {
            this.scalars.addAll(scalars)
        }

        /**
         * Build the parser with the supplied schema and dictionary.
         */
        fun build(): SchemaParser {
            return SchemaParser(Parser().parseDocument(this.schemaString.toString()), this.resolvers, this.scalars, this.dictionary)
        }
    }

    companion object {
        val DEFAULT_DEPRECATION_MESSAGE = "No longer supported"

        @JvmStatic fun newParser() = Builder()
    }

    private val allDefinitions: List<Definition> = doc.definitions
    private inline fun <reified T> getDefinitions(): List<T> = allDefinitions.filter { it is T }.map { it as T }

    private val schemaDefinitions: List<SchemaDefinition> = getDefinitions()
    private val objectDefinitions: List<ObjectTypeDefinition> = getDefinitions()
    private val inputObjectDefinitions: List<InputObjectTypeDefinition> = getDefinitions()
    private val enumDefinitions: List<EnumTypeDefinition> = getDefinitions()
    private val interfaceDefinitions: List<InterfaceTypeDefinition> = getDefinitions()
    private val unionDefinitions: List<UnionTypeDefinition> = getDefinitions()
    private val scalarDefinitions: List<ScalarTypeDefinition> = getDefinitions()

    private val userScalars = scalars.associateBy { it.name }

    // Ensure all scalar definitions have implementations and add the definition to those.
    private val scalars = scalarDefinitions.map { definition ->
        val provided = userScalars[definition.name] ?: throw SchemaError("Expected a user-defined GraphQL scalar type with name '${definition.name}' but found none!")
        GraphQLScalarType(provided.name, getDocumentation(definition) ?: provided.description, provided.coercing, definition)
    }.associateBy { it.name!! }

    val resolvers = resolvers.map { Resolver(it) }

    /**
     * Parses the given schema with respect to the given dictionary and returns GraphQL objects.
     */
    fun parseSchemaObjects(): SchemaObjects {

        // Figure out what query and mutation types are called
        val queryType = schemaDefinitions.lastOrNull()?.operationTypeDefinitions?.find { it.name == "query" }?.type as TypeName?
        val mutationType = schemaDefinitions.lastOrNull()?.operationTypeDefinitions?.find { it.name == "mutation" }?.type as TypeName?
        val queryName = queryType?.name ?: "Query"
        val mutationName = mutationType?.name ?: "Mutation"

        val typeDictionary = TypeClassDictionary(dictionary, allDefinitions, resolvers, scalars).compileDictionary(queryName, mutationName, mutationType != null)

        val resolvers = resolvers.associateBy { it.getName(dictionary) }

        // Create GraphQL objects
        val interfaces = interfaceDefinitions.map { createInterfaceObject(it) }
        val objects = objectDefinitions.map { createObject(it, resolvers, interfaces) }
        val unions = unionDefinitions.map { createUnionObject(it, objects) }
        val inputObjects = inputObjectDefinitions.map { createInputObject(it) }
        val enums = enumDefinitions.map { createEnumObject(it) }

        // Assign type resolver to interfaces now that we know all of the object types
        interfaces.forEach { (it.typeResolver as TypeResolverProxy).typeResolver = InterfaceTypeResolver(dictionary.inverse(), it, objects) }
        unions.forEach { (it.typeResolver as TypeResolverProxy).typeResolver = UnionTypeResolver(dictionary.inverse(), it, objects) }

        // Find query type and mutation type (if mutation type exists)
        val query = objects.find { it.name == queryName } ?: throw SchemaError("Expected a Query object with name '$queryName' but found none!")
        val mutation = objects.find { it.name == mutationName } ?: if(mutationType != null) throw SchemaError("Expected a Mutation object with name '$mutationName' but found none!") else null

        return SchemaObjects(query, mutation, (objects + inputObjects + enums + interfaces + unions).toSet())
    }

    /**
     * Parses the given schema with respect to the given dictionary and returns a GraphQLSchema
     */
    fun makeExecutableSchema(): GraphQLSchema = parseSchemaObjects().toSchema()

    private fun getResolver(resolvers: Map<String, Resolver>, name: String): Resolver {
        return resolvers[name] ?: getDataClassResolver(name) ?: throw SchemaError("Expected resolver or data class with name '$name' but found none!")
    }

    private fun  getDataClassResolver(name: String): Resolver? {
        return NoResolver(dictionary[name] ?: return null)
    }

    private fun createObject(definition: ObjectTypeDefinition, resolvers: Map<String, Resolver>, interfaces: List<GraphQLInterfaceType>): GraphQLObjectType {
        val name = definition.name
        val resolver = getResolver(resolvers, name)
        val builder = GraphQLObjectType.newObject()
            .name(name)
            .definition(definition)
            .description(getDocumentation(definition))

        definition.implements.forEach { implementsDefinition ->
            val interfaceName = (implementsDefinition as TypeName).name
            builder.withInterface(interfaces.find { it.name == interfaceName } ?: throw SchemaError("Expected interface type with name '$interfaceName' but found none!"))
        }

        definition.fieldDefinitions.forEach { fieldDefinition ->
            builder.field { field ->
                createFieldDefinition(field, fieldDefinition)
                field.dataFetcher(ResolverDataFetcher.create(resolver, fieldDefinition))
            }
        }

        return builder.build()
    }

    private fun createInputObject(definition: InputObjectTypeDefinition): GraphQLInputObjectType {
        val builder = GraphQLInputObjectType.newInputObject()
            .name(definition.name)
            .definition(definition)
            .description(getDocumentation(definition))

        definition.inputValueDefinitions.forEach { inputDefinition ->
            builder.field { field ->
                field.name(inputDefinition.name)
                field.definition(inputDefinition)
                field.description(getDocumentation(inputDefinition))
                field.defaultValue(inputDefinition.defaultValue)
                field.type(determineInputType(inputDefinition.type))
            }
        }

        return builder.build()
    }

    private fun createEnumObject(definition: EnumTypeDefinition): GraphQLEnumType {
        val name = definition.name
        val type = dictionary[name] ?: throw SchemaError("Expected enum with name '$name' but found none!")
        if (!type.isEnum) throw SchemaError("Type '$name' is declared as an enum in the GraphQL schema but is not a Java enum!")

        val builder = GraphQLEnumType.newEnum()
            .name(name)
            .definition(definition)
            .description(getDocumentation(definition))

        definition.enumValueDefinitions.forEach { enumDefinition ->
            val enumName = enumDefinition.name
            val enumValue = type.enumConstants.find { it.toString() == enumName } ?: throw SchemaError("Expected value for name '$enumName' in enum '${type.simpleName}' but found none!")
            getDeprecated(enumDefinition.directives).let {
                when (it) {
                    is String -> builder.value(enumName, enumValue, getDocumentation(enumDefinition), it)
                    else -> builder.value(enumName, enumValue, getDocumentation(enumDefinition))
                }
            }
        }

        return builder.build()
    }

    private fun createInterfaceObject(definition: InterfaceTypeDefinition): GraphQLInterfaceType {
        val name = definition.name
        val builder = GraphQLInterfaceType.newInterface()
            .name(name)
            .definition(definition)
            .description(getDocumentation(definition))
            .typeResolver(TypeResolverProxy())

        definition.fieldDefinitions.forEach { fieldDefinition ->
            builder.field { field -> createFieldDefinition(field, fieldDefinition) }
        }

        return builder.build()
    }

    private fun createUnionObject(definition: UnionTypeDefinition, types: List<GraphQLObjectType>): GraphQLUnionType {
        val name = definition.name
        val builder = GraphQLUnionType.newUnionType()
            .name(name)
            .definition(definition)
            .description(getDocumentation(definition))
            .typeResolver(TypeResolverProxy())

        definition.memberTypes.forEach {
            val typeName = (it as TypeName).name
            builder.possibleType(types.find { it.name == typeName } ?: throw SchemaError("Expected object type '$typeName' for union type '$name', but found none!"))
        }

        return builder.build()
    }

    private fun createFieldDefinition(field: GraphQLFieldDefinition.Builder, fieldDefinition : FieldDefinition): GraphQLFieldDefinition.Builder {
        field.name(fieldDefinition.name)
        field.description(getDocumentation(fieldDefinition))
        field.definition(fieldDefinition)
        getDeprecated(fieldDefinition.directives)?.let { field.deprecate(it) }
        field.type(determineOutputType(fieldDefinition.type))
        fieldDefinition.inputValueDefinitions.forEach { argumentDefinition ->
            field.argument { argument ->
                argument.name(argumentDefinition.name)
                argument.definition(argumentDefinition)
                argument.description(getDocumentation(argumentDefinition))
                argument.defaultValue(argumentDefinition.defaultValue)
                argument.type(determineInputType(argumentDefinition.type))
            }
        }
        return field
    }

    private fun determineOutputType(typeDefinition: Type) = determineType(typeDefinition) as GraphQLOutputType
    private fun determineInputType(typeDefinition: Type) = determineType(typeDefinition) as GraphQLInputType

    private fun determineType(typeDefinition: Type): GraphQLType =
        when (typeDefinition) {
            is ListType -> GraphQLList(determineType(typeDefinition.type))
            is NonNullType -> GraphQLNonNull(determineType(typeDefinition.type))
            is TypeName -> graphQLScalars[typeDefinition.name] ?: scalars[typeDefinition.name] ?: GraphQLTypeReference(typeDefinition.name)
            else -> throw SchemaError("Unknown type: $typeDefinition")
        }

    private fun getDocumentation(node: AbstractNode): String? = node.comments?.map { it.content.trim() }?.joinToString("\n")

    /**
     * Returns an optional [String] describing a deprecated field/enum.
     * If a deprecation directive was defined using the @deprecated directive,
     * then a String containing either the contents of the 'reason' argument, if present, or a default
     * message defined in [DEFAULT_DEPRECATION_MESSAGE] will be returned. Otherwise, [null] will be returned
     * indicating no deprecation directive was found within the directives list.
     */
    private fun getDeprecated(directives: List<Directive>): String? =
        getDirective(directives, "deprecated")?.let { directive ->
            (directive.arguments.find { it.name == "reason" }?.value as? StringValue)?.value ?:
                DEFAULT_DEPRECATION_MESSAGE
        }

    private fun getDirective(directives: List<Directive>, name: String): Directive? = directives.find {
        it.name == name
    }
}

class SchemaError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

val graphQLScalars = listOf(
    GraphQLInt,
    GraphQLLong,
    GraphQLFloat,
    GraphQLString,
    GraphQLBoolean,
    GraphQLID,
    GraphQLBigInteger,
    GraphQLBigDecimal,
    GraphQLByte,
    GraphQLShort,
    GraphQLChar
).associateBy { it.name }
