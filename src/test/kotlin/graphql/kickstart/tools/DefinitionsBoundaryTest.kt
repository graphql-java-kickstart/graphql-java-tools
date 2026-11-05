package graphql.kickstart.tools

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaConstructorCall
import com.tngtech.archunit.core.domain.JavaConstructorReference
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import graphql.kickstart.tools.directive.DirectiveWiringHelper
import graphql.kickstart.tools.relay.RelayConnectionFactory
import graphql.language.AstTransformer
import graphql.language.NodeTraverser
import graphql.language.NodeVisitor
import graphql.language.SDLExtensionDefinition
import graphql.language.SchemaExtensionDefinition
import graphql.language.TypeDefinition
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.TypeDefinitionRegistry
import org.junit.Test
import java.nio.file.Paths
import kotlin.reflect.KClass

class DefinitionsBoundaryTest {

    companion object {
        private val classes: JavaClasses = ClassFileImporter()
            .importPath(Paths.get(MergedDefinitions::class.java.protectionDomain.codeSource.location.toURI()))
    }

    private fun declaredOutside(vararg allowed: KClass<*>) = object : DescribedPredicate<JavaClass>("are not ${allowed.joinToString { it.java.name }}") {
        private val names = allowed.map { it.java.name }
        override fun test(javaClass: JavaClass) = javaClass.name.substringBefore('$') !in names
    }

    private val typeMembers = setOf(
        "getFieldDefinitions", "getImplements", "getMemberTypes", "getEnumValueDefinitions", "getInputValueDefinitions",
        "getDirectives", "getDirectivesByName", "hasDirective", "getChildren", "getNamedChildren",
    )

    private val readsTypeMembers = object : DescribedPredicate<JavaAccess<*>>("reads the members, directives or child nodes of what may be an SDL type definition") {
        override fun test(access: JavaAccess<*>): Boolean {
            val owner = access.target.owner
            return access.target.name in typeMembers &&
                (owner.isAssignableTo(TypeDefinition::class.java) || owner.isAssignableFrom(TypeDefinition::class.java))
        }
    }

    private val extensionClass = object : DescribedPredicate<JavaClass>("are graphql-java type extension definitions") {
        override fun test(javaClass: JavaClass) =
            javaClass.isAssignableTo(SDLExtensionDefinition::class.java) && !javaClass.isAssignableTo(SchemaExtensionDefinition::class.java)
    }

    private val nodeWalker = object : DescribedPredicate<JavaClass>("walk graphql-java SDL nodes") {
        override fun test(javaClass: JavaClass) = javaClass.isAssignableTo(NodeVisitor::class.java) ||
            javaClass.isEquivalentTo(NodeTraverser::class.java) || javaClass.isEquivalentTo(AstTransformer::class.java)
    }

    private val objectMethods = Any::class.java.methods.mapTo(hashSetOf()) { it.name }

    private val readsTypeRegistry = object : DescribedPredicate<JavaAccess<*>>("reads a TypeDefinitionRegistry") {
        override fun test(access: JavaAccess<*>) =
            access.target.owner.isAssignableTo(TypeDefinitionRegistry::class.java) && access.target.name !in objectMethods
    }

    private val namedTypes = listOf(
        GraphQLObjectType::class, GraphQLInterfaceType::class, GraphQLUnionType::class,
        GraphQLEnumType::class, GraphQLInputObjectType::class, GraphQLScalarType::class,
    ).mapTo(hashSetOf()) { it.java.name }
    private val builderFactories = setOf("newObject", "newInterface", "newUnionType", "newEnum", "newInputObject", "newScalar")

    private val startsNamedTypeBuilder = object : DescribedPredicate<JavaAccess<*>>("starts a builder for a graphql-java named type") {
        override fun test(access: JavaAccess<*>): Boolean {
            val owner = access.target.owner.name
            val isFactory = access.target.name in builderFactories && owner in namedTypes
            val isConstructor = (access is JavaConstructorCall || access is JavaConstructorReference) &&
                owner.substringBefore('$') in namedTypes && owner.endsWith("\$Builder")
            return isFactory || isConstructor
        }
    }

    private val transformsNamedType = object : DescribedPredicate<JavaAccess<*>>("transforms a graphql-java named type") {
        override fun test(access: JavaAccess<*>) = access.target.name == "transform" && access.target.owner.name in namedTypes
    }

    private val readMergedType = "Look the type up with MergedDefinitions.byName or resolve(name) and read MergedType.fields, interfaces, members or values, which hold the extensions' items too"

    @Test
    fun `imports the compiled main classes`() {
        assert(classes.size > 100) { "expected the compiled main classes, found ${classes.size}" }
    }

    @Test
    fun `only the merged definitions read the members and directives of SDL types`() {
        noClasses().that(declaredOutside(MergedDefinitions::class, MergedType::class, RelayConnectionFactory::class))
            .should().accessTargetWhere(readsTypeMembers)
            .because("each type is merged with its extensions once in MergedDefinitions.kt. $readMergedType. " +
                "MergedType.prepare applies the merged directives, and a built graphql-java type lists them in appliedDirectives. " +
                "A field, argument or enum value's own directives are fine to read through a FieldDefinition, InputValueDefinition or EnumValueDefinition receiver, not a DirectivesContainer or Node one")
            .check(classes)
    }

    @Test
    fun `only the merged definitions see type extension classes`() {
        noClasses().that(declaredOutside(MergedDefinitions::class, MergedType::class))
            .should().dependOnClassesThat(extensionClass)
            .because("each type is merged with its extensions once in MergedDefinitions.kt. $readMergedType. MergedType.prepare passes the extension definitions to the builder")
            .check(classes)
    }

    @Test
    fun `nothing walks SDL nodes`() {
        noClasses().should().dependOnClassesThat(nodeWalker)
            .because("a walk visits each type definition apart from its extensions. $readMergedType")
            .check(classes)
    }

    @Test
    fun `nothing reads a TypeDefinitionRegistry`() {
        noClasses().should().accessTargetWhere(readsTypeRegistry)
            .because("a TypeDefinitionRegistry keeps each type apart from its extensions. $readMergedType")
            .check(classes)
    }

    @Test
    fun `only the merged definitions start named-type builders`() {
        noClasses().that(declaredOutside(MergedType::class))
            .should().accessTargetWhere(startsNamedTypeBuilder)
            .because("every named type starts from MergedType.prepare, which checks repeats and sets the name, definitions, description and directives")
            .check(classes)
    }

    @Test
    fun `only the directive wiring transforms built named types`() {
        noClasses().that(declaredOutside(DirectiveWiringHelper::class))
            .should().accessTargetWhere(transformsNamedType)
            .because("transform copies a type that's already built. Start a new type from MergedType.prepare")
            .check(classes)
    }
}
