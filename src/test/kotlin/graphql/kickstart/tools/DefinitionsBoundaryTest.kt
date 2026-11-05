package graphql.kickstart.tools

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaConstructorCall
import com.tngtech.archunit.core.domain.JavaConstructorReference
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import graphql.language.SDLExtensionDefinition
import graphql.language.SchemaExtensionDefinition
import graphql.language.TypeDefinition
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLUnionType
import org.junit.Test
import java.nio.file.Paths

class DefinitionsBoundaryTest {

    companion object {
        private val classes: JavaClasses = ClassFileImporter()
            .importPath(Paths.get(MergedDefinitions::class.java.protectionDomain.codeSource.location.toURI()))
    }

    private fun declaredOutside(vararg files: String) = object : DescribedPredicate<JavaClass>("are declared outside ${files.joinToString()}") {
        override fun test(javaClass: JavaClass) = javaClass.source.flatMap { it.fileName }.orElse("") !in files
    }

    private val getsRawDefinitionsByContract = object : DescribedPredicate<JavaClass>("implement TypeDefinitionFactory") {
        override fun test(javaClass: JavaClass) = javaClass.isAssignableTo(TypeDefinitionFactory::class.java)
    }

    private val mergedMembers = setOf(
        "getFieldDefinitions", "getImplements", "getMemberTypes", "getEnumValueDefinitions", "getInputValueDefinitions",
        "getDirectives", "getDirectivesByName", "hasDirective",
    )

    private val readsTypeMembers = object : DescribedPredicate<JavaAccess<*>>("reads the members or directives of an SDL type definition") {
        override fun test(access: JavaAccess<*>) =
            access.target.name in mergedMembers && access.target.owner.isAssignableTo(TypeDefinition::class.java)
    }

    private val extensionClass = object : DescribedPredicate<JavaClass>("are graphql-java type extension definitions") {
        override fun test(javaClass: JavaClass) =
            javaClass.isAssignableTo(SDLExtensionDefinition::class.java) && !javaClass.isAssignableTo(SchemaExtensionDefinition::class.java)
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

    @Test
    fun `imports the compiled main classes`() {
        assert(classes.size > 100) { "expected the compiled main classes, found ${classes.size}" }
    }

    @Test
    fun `only the merged definitions read the members and directives of SDL types`() {
        noClasses().that(declaredOutside("MergedDefinitions.kt")).and(DescribedPredicate.not(getsRawDefinitionsByContract))
            .should().accessTargetWhere(readsTypeMembers)
            .because("each type is merged with its extensions once in MergedDefinitions.kt. Take a MergedType and read MergedType.fields, interfaces, members or values, and let MergedType.prepare apply the directives")
            .check(classes)
    }

    @Test
    fun `only the merged definitions see type extension classes`() {
        noClasses().that(declaredOutside("MergedDefinitions.kt")).and(DescribedPredicate.not(getsRawDefinitionsByContract))
            .should().dependOnClassesThat(extensionClass)
            .because("each type is merged with its extensions once in MergedDefinitions.kt. Take a MergedType instead of matching extension definitions")
            .check(classes)
    }

    @Test
    fun `only the merged definitions start named-type builders`() {
        noClasses().that(declaredOutside("MergedDefinitions.kt"))
            .should().accessTargetWhere(startsNamedTypeBuilder)
            .because("every named type starts from MergedType.prepare, which checks repeats and sets the name, definitions, description and directives")
            .check(classes)
    }

    @Test
    fun `only the directive wiring transforms built named types`() {
        noClasses().that(declaredOutside("MergedDefinitions.kt", "DirectiveWiringHelper.kt"))
            .should().accessTargetWhere(transformsNamedType)
            .because("transform copies a type that's already built. Start a new type from MergedType.prepare")
            .check(classes)
    }
}
