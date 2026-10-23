package graphql.kickstart.tools

import graphql.kickstart.tools.util.describe
import graphql.kickstart.tools.util.unwrap
import graphql.language.*
import graphql.schema.GraphQLInputType
import kotlin.reflect.KClass

internal class TypeReferenceErrors(
    private val definitions: Set<SDLNamedDefinition<*>>,
    private val unusedDefinitions: Set<TypeDefinition<*>>,
    private val typeExtensions: TypeExtensions,
) {
    fun unexpectedTypeError(typeName: TypeName, expectedType: KClass<*>): SchemaError {
        val isInput = expectedType == GraphQLInputType::class
        val definition = unusedDefinitions.find { it.name == typeName.name }
            ?: definitions.filterIsInstance<TypeDefinition<*>>().find { it.name == typeName.name }
        val outputOnlyKind = when (definition) {
            is ObjectTypeDefinition -> "an object type"
            is InterfaceTypeDefinition -> "an interface"
            is UnionTypeDefinition -> "a union"
            else -> null
        }

        val problem = when {
            definition == null -> "is not defined in the schema."
            isInput && outputOnlyKind != null ->
                "is $outputOnlyKind, which can't be used as an input type. Input types are input objects, enums and scalars."
            !isInput && definition is InputObjectTypeDefinition ->
                "is an input object type, which can't be used as an output type. Output types are object types, interfaces, unions, enums and scalars."
            definition in unusedDefinitions ->
                "is defined in the schema, but was not reached while scanning the resolvers and the classes they use, so it wasn't built. " +
                    unreachedTypeHint(definition)
            else -> "can't be used as ${if (isInput) "an input" else "an output"} type."
        }
        val usage = findUsage(typeName)?.let { " used by $it" }.orEmpty()
        return SchemaError("Type '${typeName.name}'$usage${typeName.sourceLocation.describe()} $problem")
    }

    // what makes the scanner reach the type depends on its kind, see SchemaClassScanner.canIncludeUnusedType
    private fun unreachedTypeHint(definition: TypeDefinition<*>): String = when (definition) {
        is InterfaceTypeDefinition, is UnionTypeDefinition ->
            "Return it from a resolver method or one of those classes, or set SchemaParserOptions.includeUnusedTypes."
        is EnumTypeDefinition ->
            "Use it in a resolver method or one of those classes, or set SchemaParserOptions.includeUnusedTypes and pass its enum class in the parser's dictionary."
        is InputObjectTypeDefinition ->
            "Use it in an argument of a resolver method, directly or through another input type. SchemaParserOptions.includeUnusedTypes doesn't include input types."
        is ScalarTypeDefinition ->
            "Use it in a resolver method or one of those classes. SchemaParserOptions.includeUnusedTypes doesn't include scalars."
        else ->
            "Return it from a resolver method or one of those classes, or set SchemaParserOptions.includeUnusedTypes and pass a class for it in the parser's dictionary."
    }

    // determineType only gets the type reference, so the definition using it is looked up for the error message
    private fun findUsage(typeName: TypeName): String? = (definitions + typeExtensions.values.flatten()).asSequence().flatMap { definition ->
        when (definition) {
            is ObjectTypeDefinition -> fieldUsages(definition.name, definition.fieldDefinitions)
            is InterfaceTypeDefinition -> fieldUsages(definition.name, definition.fieldDefinitions)
            is InputObjectTypeDefinition -> definition.inputValueDefinitions.map { "input field '${definition.name}.${it.name}'" to it.type }
            is DirectiveDefinition -> definition.inputValueDefinitions.map { "argument '${it.name}' of directive '@${definition.name}'" to it.type }
            else -> emptyList()
        }
    }.find { (_, type) -> type.unwrap() === typeName }?.first

    private fun fieldUsages(typeName: String, fields: List<FieldDefinition>): List<Pair<String, Type<*>>> = fields.flatMap { field ->
        listOf("field '$typeName.${field.name}'" to field.type) +
            field.inputValueDefinitions.map { "argument '${it.name}' of field '$typeName.${field.name}'" to it.type }
    }
}
