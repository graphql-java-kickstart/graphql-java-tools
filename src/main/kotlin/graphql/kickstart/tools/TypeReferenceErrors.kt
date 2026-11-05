package graphql.kickstart.tools

import graphql.kickstart.tools.util.describe
import graphql.language.TypeName
import graphql.schema.GraphQLInputType
import kotlin.reflect.KClass

internal class TypeReferenceErrors(
    private val definitions: MergedDefinitions,
    private val types: Set<MergedType<*>>,
    private val unusedTypes: Set<MergedType<*>>,
) {
    fun unexpectedTypeError(typeName: TypeName, expectedType: KClass<*>): SchemaError {
        val isInput = expectedType == GraphQLInputType::class
        val definition = unusedTypes.find { it.name == typeName.name }
            ?: types.find { it.name == typeName.name }
        val outputOnlyKind = definition?.takeIf { it is MergedType.Object || it is MergedType.Interface || it is MergedType.Union }?.kind

        val problem = when {
            definition == null -> "is not defined in the schema."
            isInput && outputOnlyKind != null ->
                "is $outputOnlyKind, which can't be used as an input type. Input types are input objects, enums and scalars."
            !isInput && definition is MergedType.Input ->
                "is an input object type, which can't be used as an output type. Output types are object types, interfaces, unions, enums and scalars."
            definition in unusedTypes ->
                "is defined in the schema, but was not reached while scanning the resolvers and the classes they use, so it wasn't built. " +
                    unreachedTypeHint(definition)
            else -> "can't be used as ${if (isInput) "an input" else "an output"} type."
        }
        val usage = definitions.findUsage(typeName, types)?.let { " used by $it" }.orEmpty()
        return SchemaError("Type '${typeName.name}'$usage${typeName.sourceLocation.describe()} $problem")
    }

    // what makes the scanner reach the type depends on its kind, see SchemaClassScanner.canIncludeUnusedType
    private fun unreachedTypeHint(definition: MergedType<*>): String = when (definition) {
        is MergedType.Interface, is MergedType.Union ->
            "Return it from a resolver method or one of those classes, or set SchemaParserOptions.includeUnusedTypes."
        is MergedType.Enum ->
            "Use it in a resolver method or one of those classes, or set SchemaParserOptions.includeUnusedTypes and pass its enum class in the parser's dictionary."
        is MergedType.Input ->
            "Use it in an argument of a resolver method, directly or through another input type. SchemaParserOptions.includeUnusedTypes doesn't include input types."
        is MergedType.Scalar ->
            "Use it in a resolver method or one of those classes. SchemaParserOptions.includeUnusedTypes doesn't include scalars."
        is MergedType.Object ->
            "Return it from a resolver method or one of those classes, or set SchemaParserOptions.includeUnusedTypes and pass a class for it in the parser's dictionary."
    }
}
