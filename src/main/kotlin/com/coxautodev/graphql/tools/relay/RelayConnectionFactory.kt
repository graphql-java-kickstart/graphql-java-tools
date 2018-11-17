package com.coxautodev.graphql.tools.relay

import com.coxautodev.graphql.tools.TypeDefinitionFactory
import graphql.language.Definition
import graphql.language.Directive
import graphql.language.FieldDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.StringValue
import graphql.language.TypeDefinition
import graphql.language.TypeName

class RelayConnectionFactory : TypeDefinitionFactory {

    override fun create(existing: List<Definition<*>>): List<Definition<*>> {
        val definitions = mutableListOf<Definition<*>>()
        val definitionsByName = existing.filterIsInstance<TypeDefinition<*>>()
                .associateBy { it.name }
                .toMutableMap()

        findConnectionDirectives(existing)
                .flatMap { createDefinitions(it) }
                .forEach {
                    if (!definitionsByName.containsKey(it.name)) {
                        definitionsByName[it.name] = it
                        definitions.add(it)
                    }
                }

        if (!definitionsByName.containsKey("PageInfo")) {
            definitions.add(createPageInfo())
        }

        return definitions
    }

    private fun findConnectionDirectives(definitions: List<Definition<*>>): List<Directive> {
        return definitions.filterIsInstance<ObjectTypeDefinition>()
                .flatMap { it.fieldDefinitions }
                .flatMap { it.directives }
                .filter { it.name == "connection" }
    }

    private fun createDefinitions(directive: Directive): List<ObjectTypeDefinition> {
        val definitions = mutableListOf<ObjectTypeDefinition>()
        definitions.add(createEdgeDefinition(directive.forTypeName()))
        definitions.add(createConnectionDefinition(directive.forTypeName()))
        return definitions.toList()
    }

    private fun createConnectionDefinition(type: String): ObjectTypeDefinition =
            ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(type + "Connection")
                    .fieldDefinition(FieldDefinition("edges", ListType(TypeName(type + "Edge"))))
                    .fieldDefinition(FieldDefinition("pageInfo", TypeName("PageInfo")))
                    .build()

    private fun createEdgeDefinition(type: String): ObjectTypeDefinition =
            ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(type + "Edge")
                    .fieldDefinition(FieldDefinition("cursor", TypeName("String")))
                    .fieldDefinition(FieldDefinition("node", TypeName(type)))
                    .build()

    private fun createPageInfo(): ObjectTypeDefinition =
            ObjectTypeDefinition.newObjectTypeDefinition()
                    .name("PageInfo")
                    .fieldDefinition(FieldDefinition("hasPreviousPage", NonNullType(TypeName("Boolean"))))
                    .fieldDefinition(FieldDefinition("hasNextPage", NonNullType(TypeName("Boolean"))))
                    .build()

    private fun Directive.forTypeName(): String {
        return (this.getArgument("for").value as StringValue).value
    }

}
