package com.coxautodev.graphql.tools

import graphql.language.AbstractNode
import graphql.language.Directive
import graphql.language.EnumTypeDefinition
import graphql.language.FieldDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.StringValue
import graphql.language.Type
import graphql.language.TypeDefinition
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
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
import graphql.schema.idl.ScalarInfo

/**
 * Parses a GraphQL Schema and maps object fields to provided class methods.
 *
 * @author Andrew Potter
 */
class SchemaParser internal constructor(private val dictionary: TypeClassDictionary, definitions: Set<TypeDefinition>, private val customScalars: CustomScalarMap, private val rootInfo: RootTypeInfo, private val methodsByObjectField: Map<ObjectTypeDefinition, MutableMap<FieldDefinition, Resolver.Method>>) {

    companion object {
        val DEFAULT_DEPRECATION_MESSAGE = "No longer supported"

        @JvmStatic fun newParser() = SchemaParserBuilder()
        internal fun getDocumentation(node: AbstractNode): String? = node.comments?.map { it.content.trim() }?.joinToString("\n")
    }

    private val objectDefinitions = definitions.filterIsInstance<ObjectTypeDefinition>()
    private val inputObjectDefinitions = definitions.filterIsInstance<InputObjectTypeDefinition>()
    private val enumDefinitions = definitions.filterIsInstance<EnumTypeDefinition>()
    private val interfaceDefinitions = definitions.filterIsInstance<InterfaceTypeDefinition>()
    private val unionDefinitions = definitions.filterIsInstance<UnionTypeDefinition>()

    /**
     * Parses the given schema with respect to the given dictionary and returns GraphQL objects.
     */
    fun parseSchemaObjects(): SchemaObjects {

        // Create GraphQL objects
        val interfaces = interfaceDefinitions.map { createInterfaceObject(it) }
        val objects = objectDefinitions.map { createObject(it, interfaces) }
        val unions = unionDefinitions.map { createUnionObject(it, objects) }
        val inputObjects = inputObjectDefinitions.map { createInputObject(it) }
        val enums = enumDefinitions.map { createEnumObject(it) }

        // Assign type resolver to interfaces now that we know all of the object types
        interfaces.forEach { (it.typeResolver as TypeResolverProxy).typeResolver = InterfaceTypeResolver(dictionary.inverse(), it, objects) }
        unions.forEach { (it.typeResolver as TypeResolverProxy).typeResolver = UnionTypeResolver(dictionary.inverse(), it, objects) }

        // Find query type and mutation type (if mutation type exists)
        val queryName = rootInfo.getQueryName()
        val mutationName = rootInfo.getMutationName()

        val query = objects.find { it.name == queryName } ?: throw SchemaError("Expected a Query object with name '$queryName' but found none!")
        val mutation = objects.find { it.name == mutationName } ?: if(rootInfo.isMutationRequired()) throw SchemaError("Expected a Mutation object with name '$mutationName' but found none!") else null

        return SchemaObjects(query, mutation, (objects + inputObjects + enums + interfaces + unions).toSet())
    }

    /**
     * Parses the given schema with respect to the given dictionary and returns a GraphQLSchema
     */
    fun makeExecutableSchema(): GraphQLSchema = parseSchemaObjects().toSchema()

    private fun createObject(definition: ObjectTypeDefinition, interfaces: List<GraphQLInterfaceType>): GraphQLObjectType {
        val name = definition.name
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
                createField(field, fieldDefinition)
                field.dataFetcher(ResolverDataFetcher.create(methodsByObjectField[definition]?.get(fieldDefinition) ?: throw SchemaError("No resolver method found for object type '${definition.name}' and field '${fieldDefinition.name}', this is most likely a bug with graphql-java-tools")))
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
        val type = dictionary[definition] ?: throw SchemaError("Expected enum with name '$name' but found none!")
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
            builder.field { field -> createField(field, fieldDefinition) }
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

    private fun createField(field: GraphQLFieldDefinition.Builder, fieldDefinition : FieldDefinition): GraphQLFieldDefinition.Builder {
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
            is TypeName -> graphQLScalars[typeDefinition.name] ?: customScalars[typeDefinition.name] ?: GraphQLTypeReference(typeDefinition.name)
            else -> throw SchemaError("Unknown type: $typeDefinition")
        }

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

val graphQLScalars = ScalarInfo.STANDARD_SCALARS.associateBy { it.name }

typealias CustomScalarMap = Map<String, GraphQLScalarType>
