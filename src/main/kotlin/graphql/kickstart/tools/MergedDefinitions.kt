package graphql.kickstart.tools

import graphql.Directives
import graphql.introspection.Introspection.DirectiveLocation
import graphql.kickstart.tools.util.describe
import graphql.kickstart.tools.util.getDocumentation
import graphql.kickstart.tools.util.unwrap
import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.ScalarInfo

/**
 * The SDL types of a schema, each merged with its extensions. Outside this file the scanner and the parser only see
 * [MergedType]s, so every reader of a type's fields, members, values or directives gets those of its extensions too.
 */
internal class MergedDefinitions(definitions: List<Definition<*>>) {
    val directiveDefinitions: List<DirectiveDefinition> = definitions.filterIsInstance<DirectiveDefinition>()
    val rootInfo: RootTypeInfo = RootTypeInfo.fromSchemaDefinitions(definitions.filterIsInstance<SchemaDefinition>())

    // includes the names of scalar extensions, even ignored ones
    val scalarNames: Set<String> = ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.keys + definitions.filterIsInstance<ScalarTypeDefinition>().map { it.name }

    private val typeDefinitions = definitions.filterIsInstance<TypeDefinition<*>>()
    private val baseTypeNames = typeDefinitions.filter { it !is SDLExtensionDefinition }.map { it.name }.toSet()

    // the first extension of an interface, union, enum or scalar without a definition stands in for it
    private val loneExtensions = typeDefinitions
        .filter { it is SDLExtensionDefinition && it !is ObjectTypeDefinition && it !is InputObjectTypeDefinition && it.name !in baseTypeNames }
        .distinctBy { it.name }
        .toSet()

    private val sources = typeDefinitions.filter { it !is SDLExtensionDefinition || it in loneExtensions }

    // the last definition of a name wins
    private val sourcesByName = sources.associateBy { it.name }

    private val extensionsByName = typeDefinitions
        .filter { it is SDLExtensionDefinition && it !in loneExtensions && !extendsOtherKind(it) }
        .groupBy { it.name }

    // one type per definition, so a repeated definition of a name is a type of its own
    // one type per definition, so a repeated definition of a name is a type of its own
    private val typesBySource: List<Pair<TypeDefinition<*>, MergedType<*>>> = sources.mapNotNull { source -> merge(source)?.let { source to it } }

    private val specificationScalars: List<Pair<ScalarTypeDefinition, MergedType.Scalar>> = ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.values.map { definition ->
        definition to MergedType.Scalar(definition, extensionsByName[definition.name].orEmpty().filterIsInstance<ScalarTypeExtensionDefinition>())
    }

    private val specificationScalarsByName: Map<String, MergedType.Scalar> = specificationScalars.associate { (_, type) -> type.name to type }

    private val sourceOf: Map<MergedType<*>, TypeDefinition<*>> = (typesBySource + specificationScalars).associate { (source, type) -> type to source }

    val byName: Map<String, MergedType<*>> = typesBySource.associate { (_, type) -> type.name to type }

    /**
     * Every object type definition, including repeated ones that [byName] doesn't return.
     */
    val baseObjects: List<MergedType.Object> = typesBySource.map { it.second }.filterIsInstance<MergedType.Object>()

    val ignoredExtensionWarnings: List<String> = typeDefinitions.filter(::extendsOtherKind).map { extension ->
        "Type '${extension.name}' is defined as ${sourcesByName.getValue(extension.name).kind()}, but extended as ${extension.kind()}. The extension is ignored."
    }

    /**
     * Looks a name up like graphql-java, so the specification's scalars come first.
     */
    fun resolve(name: String?): MergedType<*>? = specificationScalarsByName[name] ?: byName[name]

    fun definitionsOf(types: Set<MergedType<*>>): Set<TypeDefinition<*>> = types.mapNotNullTo(linkedSetOf()) { sourceOf[it] }

    /**
     * Finds the field, argument or input field whose type is [typeName] itself, not another reference to the same name. The
     * definitions of [types] are searched first, then the directive definitions, then every extension.
     */
    fun findUsage(typeName: TypeName, types: Collection<MergedType<*>>): String? =
        (types.mapNotNull { sourceOf[it] } + directiveDefinitions + extensionsByName.values.flatten()).asSequence().flatMap { definition ->
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

    private fun merge(source: TypeDefinition<*>): MergedType<*>? {
        val extensions = extensionsByName[source.name].orEmpty()
        return when (source) {
            is ObjectTypeDefinition -> MergedType.Object(source, extensions.filterIsInstance<ObjectTypeExtensionDefinition>())
            is InterfaceTypeDefinition -> MergedType.Interface(source, extensions.filterIsInstance<InterfaceTypeExtensionDefinition>())
            is UnionTypeDefinition -> MergedType.Union(source, extensions.filterIsInstance<UnionTypeExtensionDefinition>())
            is EnumTypeDefinition -> MergedType.Enum(source, extensions.filterIsInstance<EnumTypeExtensionDefinition>())
            is InputObjectTypeDefinition -> MergedType.Input(source, extensions.filterIsInstance<InputObjectTypeExtensionDefinition>())
            is ScalarTypeDefinition -> MergedType.Scalar(source, extensions.filterIsInstance<ScalarTypeExtensionDefinition>())
            else -> null
        }
    }

    private fun extendsOtherKind(definition: TypeDefinition<*>): Boolean {
        val type = sourcesByName[definition.name]
        return definition is SDLExtensionDefinition && type != null && type.kind() != definition.kind()
    }
}

private fun TypeDefinition<*>.kind(): String = when (this) {
    is ObjectTypeDefinition -> "an object type"
    is InterfaceTypeDefinition -> "an interface"
    is UnionTypeDefinition -> "a union"
    is EnumTypeDefinition -> "an enum"
    is InputObjectTypeDefinition -> "an input object type"
    is ScalarTypeDefinition -> "a scalar"
    else -> javaClass.simpleName
}

internal class TypeBuildContext(
    val options: SchemaParserOptions,
    val directives: SdlDirectives,
    val customScalars: CustomScalarMap,
)

/**
 * An SDL type merged with its extensions: the members of the type come first, then those of each extension in the order
 * they're declared. Only this file sees the definitions themselves.
 */
internal sealed class MergedType<B : GraphqlDirectivesContainerTypeBuilder<B, B>>(
    private val definition: TypeDefinition<*>,
    extensions: List<TypeDefinition<*>>,
) {
    val name: String get() = definition.name

    val definitionClassName: String get() = definition.javaClass.simpleName

    /**
     * The kind as the library's messages name it, such as "an object type".
     */
    val kind: String get() = definition.kind()

    protected val directives: List<Directive> = definition.directives + extensions.flatMap { it.directives }

    protected abstract val location: DirectiveLocation

    protected abstract val declarations: List<NamedNode<*>>

    protected abstract fun newBuilder(context: TypeBuildContext): B

    /**
     * Starts the graphql-java type with what every kind gets: the check for repeated declarations, the name, the definitions,
     * the description and the SDL directives. The caller adds the members.
     */
    fun prepare(context: TypeBuildContext): B {
        rejectRepeated()
        val builder = newBuilder(context).name(name)
        getDocumentation(definition as AbstractNode<*>, context.options)?.let { builder.description(it) }
        return with(context.directives) { builder.withSdlDirectives(directives, location) }
    }

    private fun rejectRepeated() {
        val noun = when (this) {
            is Object, is Interface, is Input -> "field"
            is Enum -> "value"
            is Union -> "member"
            is Scalar -> return
        }
        val names = mutableSetOf<String?>()
        val repeat = declarations.firstOrNull { !names.add(it.name) } ?: return
        throw SchemaError("Type '$name' declares $noun '${repeat.name}' more than once${repeat.sourceLocation.describe()}.")
    }

    sealed interface Implementing {
        val fields: List<FieldDefinition>
        val interfaces: List<Type<*>>
    }

    class Object(
        private val definition: ObjectTypeDefinition,
        private val extensions: List<ObjectTypeExtensionDefinition>,
    ) : MergedType<GraphQLObjectType.Builder>(definition, extensions), Implementing {
        override val fields: List<FieldDefinition> = definition.fieldDefinitions + extensions.flatMap { it.fieldDefinitions }
        override val interfaces: List<Type<*>> = definition.implements + extensions.flatMap { it.implements }
        override val location = DirectiveLocation.OBJECT
        override val declarations get() = fields
        override fun newBuilder(context: TypeBuildContext): GraphQLObjectType.Builder =
            GraphQLObjectType.newObject().definition(definition).extensionDefinitions(extensions)
    }

    class Interface(
        private val definition: InterfaceTypeDefinition,
        private val extensions: List<InterfaceTypeExtensionDefinition>,
    ) : MergedType<GraphQLInterfaceType.Builder>(definition, extensions), Implementing {
        override val fields: List<FieldDefinition> = definition.fieldDefinitions + extensions.flatMap { it.fieldDefinitions }
        override val interfaces: List<Type<*>> = definition.implements + extensions.flatMap { it.implements }
        override val location = DirectiveLocation.INTERFACE
        override val declarations get() = fields
        override fun newBuilder(context: TypeBuildContext): GraphQLInterfaceType.Builder =
            GraphQLInterfaceType.newInterface().definition(definition).extensionDefinitions(extensions)
    }

    class Union(
        private val definition: UnionTypeDefinition,
        private val extensions: List<UnionTypeExtensionDefinition>,
    ) : MergedType<GraphQLUnionType.Builder>(definition, extensions) {
        val members: List<Type<*>> = definition.memberTypes + extensions.flatMap { it.memberTypes }
        override val location = DirectiveLocation.UNION
        override val declarations get() = members.map { it as TypeName }
        override fun newBuilder(context: TypeBuildContext): GraphQLUnionType.Builder =
            GraphQLUnionType.newUnionType().definition(definition).extensionDefinitions(extensions)

        /**
         * The toString of the definition or extension that lists [member].
         */
        fun declarationOf(member: Type<*>): String =
            (listOf(definition) + extensions).first { declaration -> declaration.memberTypes.any { it === member } }.toString()
    }

    class Enum(
        private val definition: EnumTypeDefinition,
        private val extensions: List<EnumTypeExtensionDefinition>,
    ) : MergedType<GraphQLEnumType.Builder>(definition, extensions) {
        val values: List<EnumValueDefinition> = definition.enumValueDefinitions + extensions.flatMap { it.enumValueDefinitions }
        override val location = DirectiveLocation.ENUM
        override val declarations get() = values
        override fun newBuilder(context: TypeBuildContext): GraphQLEnumType.Builder =
            GraphQLEnumType.newEnum().definition(definition).extensionDefinitions(extensions)
    }

    class Input(
        private val definition: InputObjectTypeDefinition,
        private val extensions: List<InputObjectTypeExtensionDefinition>,
    ) : MergedType<GraphQLInputObjectType.Builder>(definition, extensions) {
        val fields: List<InputValueDefinition> = definition.inputValueDefinitions + extensions.flatMap { it.inputValueDefinitions }
        override val location = DirectiveLocation.INPUT_OBJECT
        override val declarations get() = fields
        override fun newBuilder(context: TypeBuildContext): GraphQLInputObjectType.Builder =
            GraphQLInputObjectType.newInputObject().definition(definition).extensionDefinitions(extensions)
    }

    class Scalar(
        private val definition: ScalarTypeDefinition,
        private val extensions: List<ScalarTypeExtensionDefinition>,
    ) : MergedType<GraphQLScalarType.Builder>(definition, extensions) {
        override val location = DirectiveLocation.SCALAR
        override val declarations = emptyList<NamedNode<*>>()

        // like graphql-java, only the schema's directives are applied
        override fun newBuilder(context: TypeBuildContext): GraphQLScalarType.Builder {
            val provided = context.customScalars.getValue(name)
            return GraphQLScalarType.newScalar(provided)
                .definition(definition)
                .extensionDefinitions(extensions)
                .specifiedByUrl(specifiedByUrl() ?: provided.specifiedByUrl)
                .clearDirectives()
        }

        private fun specifiedByUrl(): String? =
            (directives.find { it.name == Directives.SpecifiedByDirective.name }?.getArgument("url")?.value as? StringValue)?.value
    }
}
