package probe

import graphql.language.AstPrinter
import graphql.language.Node
import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import graphql.schema.InputValueWithState

fun dumpDirectives(schema: GraphQLSchema): String = buildString {
    @Suppress("DEPRECATION")
    section("schema", schema.schemaAppliedDirectives, schema.schemaDirectives)
    schema.directives.sortedBy { it.name }.forEach { definition ->
        definition.arguments.forEach { container("directive @${definition.name}(${it.name})", it) }
    }
    schema.allTypesAsList.filter { !it.name.startsWith("__") }.sortedBy { it.name }.forEach { type ->
        if (type is GraphQLDirectiveContainer) container("type ${type.name}", type)
        when (type) {
            is GraphQLFieldsContainer -> type.fieldDefinitions.forEach { field ->
                container("field ${type.name}.${field.name}", field)
                field.arguments.forEach { container("argument ${type.name}.${field.name}(${it.name})", it) }
            }
            is GraphQLInputObjectType -> type.fieldDefinitions.forEach { container("input field ${type.name}.${it.name}", it) }
            is GraphQLEnumType -> type.values.forEach { container("enum value ${type.name}.${it.name}", it) }
        }
    }
}

@Suppress("DEPRECATION")
private fun StringBuilder.container(label: String, element: GraphQLDirectiveContainer) =
    section(label, element.appliedDirectives, element.directives)

private fun StringBuilder.section(label: String, applied: List<GraphQLAppliedDirective>, legacy: List<GraphQLDirective>) {
    if (applied.isEmpty() && legacy.isEmpty()) return
    appendLine(label)
    applied.forEach { directive ->
        appendLine("  applied @${directive.name} description=${directive.description} definition=${directive.definition?.sourceLocation?.let { "${it.line}:${it.column}" }}")
        directive.arguments.forEach {
            appendLine("    ${it.name}: ${GraphQLTypeUtil.simplePrint(it.type)} = ${describe(it.argumentValue)} description=${it.description}")
        }
    }
    legacy.forEach { directive ->
        appendLine("  legacy @${directive.name} description=${directive.description} repeatable=${directive.isRepeatable} locations=${directive.validLocations()}")
        directive.arguments.forEach { appendLine("    ${legacyArgument(it)}") }
    }
}

@Suppress("DEPRECATION")
private fun legacyArgument(argument: GraphQLArgument) =
    "${argument.name}: ${GraphQLTypeUtil.simplePrint(argument.type)} = ${describe(argument.argumentValue)} description=${argument.description}"

private fun describe(value: InputValueWithState): String = when {
    value.isNotSet -> "<not set>"
    value.isLiteral -> "literal ${AstPrinter.printAst(value.value as Node<*>)}"
    value.isExternal -> "external ${value.value}"
    else -> "internal ${value.value}"
}
