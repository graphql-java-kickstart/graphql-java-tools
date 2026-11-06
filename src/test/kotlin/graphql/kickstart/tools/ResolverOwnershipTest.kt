package graphql.kickstart.tools

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import graphql.execution.DataFetcherResult
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper
import graphql.kickstart.tools.resolver.ArgumentConverter
import graphql.kickstart.tools.resolver.ResolverPlan
import graphql.kickstart.tools.resolver.TypeShape
import graphql.schema.DataFetcher
import org.apache.commons.lang3.reflect.FieldUtils
import org.junit.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.ObjectWriter
import tools.jackson.databind.util.TokenBuffer
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.file.Paths

class ResolverOwnershipTest {

    companion object {
        private val classes: JavaClasses = ClassFileImporter()
            .importPath(Paths.get(ResolverPlan::class.java.protectionDomain.codeSource.location.toURI()))

        private val resolverPackage = ResolverPlan::class.java.packageName
        private const val MEMBER = "graphql.kickstart.tools.resolver.Member"
    }

    private fun declaredIn(fileName: String) = object : DescribedPredicate<JavaClass>("are declared in $resolverPackage's $fileName") {
        override fun test(javaClass: JavaClass) =
            javaClass.packageName == resolverPackage && javaClass.source.flatMap { it.fileName }.orElse(null) == fileName
    }

    private fun DescribedPredicate<JavaClass>.orOwnerOf(vararg allowed: Class<*>): DescribedPredicate<JavaClass> {
        val names = allowed.map { it.name }
        return object : DescribedPredicate<JavaClass>("${this@orOwnerOf.description} or are ${names.joinToString()}") {
            override fun test(javaClass: JavaClass) = this@orOwnerOf.test(javaClass) || javaClass.name.substringBefore('$') in names
        }
    }

    private class Calls(description: String, private val targets: Map<String, Set<String>>) : DescribedPredicate<JavaAccess<*>>(description) {
        override fun test(access: JavaAccess<*>) =
            targets.any { (owner, names) -> access.target.owner.isAssignableTo(owner) && access.target.name in names }
    }

    private val memberReflection = Calls(
        "reads, invokes or opens a Java member by reflection",
        mapOf(
            Method::class.java.name to setOf("invoke"),
            Field::class.java.name to setOf("get", "getGenericType"),
            Executable::class.java.name to setOf("getGenericParameterTypes", "getGenericReturnType", "getParameterTypes", "getParameterCount"),
            AccessibleObject::class.java.name to setOf("setAccessible", "trySetAccessible"),
            "kotlin.reflect.jvm.ReflectJvmMapping" to setOf("getKotlinFunction"),
            FieldUtils::class.java.name to setOf("getAllFields", "getAllFieldsList"),
        )
    )

    // input-object field types are guessed from getters until they come from the mapping Jackson uses
    private val guessesInputFieldTypes = object : DescribedPredicate<JavaAccess<*>>("is in ScanDictionary.findInputValueTypeInType") {
        override fun test(access: JavaAccess<*>) =
            access.origin.owner.isEquivalentTo(ScanDictionary::class.java) && access.origin.name.startsWith("findInputValueTypeInType")
    }

    private val tokenBufferConversion = Calls(
        "converts a value through a Jackson TokenBuffer",
        mapOf(
            TokenBuffer::class.java.name to setOf("<init>", "forInputParsing", "forGeneration", "asParser"),
            ObjectWriter::class.java.name to setOf("writeValueIntoBuffer"),
            ObjectMapper::class.java.name to setOf("readValue", "convertValue", "treeToValue", "valueToTree"),
            ObjectReader::class.java.name to setOf("readValue", "readValues", "treeToValue"),
        )
    )

    private val argumentConversion = Calls(
        "builds or calls an ArgumentConverter",
        mapOf(ArgumentConverter::class.java.name to setOf("<init>", "convert"))
    )

    private val genericWrapperDispatch = Calls(
        "reads a generic wrapper's type, schema wrapper or transformer",
        mapOf(GenericWrapper::class.java.name to setOf("getType", "getIndex", "getSchemaWrapper", "getTransformer", "getTransformerReturnsCompletionStage"))
    )

    private val buildsAnUnwrapper = object : DescribedPredicate<JavaAccess<*>>("builds a TypeShape.Unwrapper") {
        override fun test(access: JavaAccess<*>) =
            access.target.owner.isEquivalentTo(TypeShape.Unwrapper::class.java) && access.target.name == "<init>"
    }

    private val holdsAnUnwrapper =object : ArchCondition<JavaClass>("hold a TypeShape.Unwrapper") {
        override fun check(javaClass: JavaClass, events: ConditionEvents) {
            if (javaClass.allFields.none { it.rawType.isEquivalentTo(TypeShape.Unwrapper::class.java) }) {
                events.add(SimpleConditionEvent.violated(javaClass, "${javaClass.name} implements DataFetcher without a TypeShape.Unwrapper field"))
            }
        }
    }

    @Test
    fun `imports the compiled resolver classes`() {
        assert(classes.contain(MEMBER)) { "expected $MEMBER, the member variants' base class in ResolverPlan.kt, among ${classes.size} classes" }
        assert(classes.any { declaredIn("ResolverPlan.kt").test(it) }) { "no class compiled from ResolverPlan.kt was imported" }
    }

    @Test
    fun `only the resolver plan reflects on resolver members`() {
        noClasses().that(DescribedPredicate.not(declaredIn("ResolverPlan.kt")))
            .should().accessTargetWhere(DescribedPredicate.and(memberReflection, DescribedPredicate.not(guessesInputFieldTypes)))
            .because("ResolverPlan.kt reads each field's member once, at schema build, and is the only place that invokes it. " +
                "Read what you need from the ResolverPlan, such as its argument targets or type shapes, or add it there")
            .check(classes)
    }

    @Test
    fun `member variants expose raw invocation, never a data fetcher`() {
        noClasses().that().areAssignableTo(MEMBER)
            .should().dependOnClassesThat().areAssignableTo(DataFetcher::class.java)
            .orShould().dependOnClassesThat().areAssignableTo(TypeShape.Unwrapper::class.java)
            .because("ResolverPlan builds every data fetcher around a member's read and unwraps its value with TypeShape.Unwrapper, " +
                "so a member that builds its own fetcher skips the unwrapping. Give the member a read and let ResolverPlan.dataFetcher wrap it")
            .check(classes)
    }

    @Test
    fun `every library data fetcher unwraps through TypeShape`() {
        classes().that().implement(DataFetcher::class.java)
            .should(holdsAnUnwrapper)
            .because("a resolver's value reaches graphql-java fully unwrapped only through TypeShape.Unwrapper. Add the member to ResolverPlan instead of a new fetcher")
            .check(classes)
    }

    @Test
    fun `only the resolver plan builds an unwrapper`() {
        noClasses().that(DescribedPredicate.not(declaredIn("ResolverPlan.kt").orOwnerOf(TypeShape::class.java)))
            .should().accessTargetWhere(buildsAnUnwrapper)
            .because("ResolverPlan.dataFetcher builds one unwrapper per field from the type shape the scan matched")
            .check(classes)
    }

    @Test
    fun `only TypeShape dispatches on containers`() {
        noClasses().that(DescribedPredicate.not(declaredIn("TypeShape.kt").orOwnerOf(GenericWrapper::class.java)))
            .should().accessTargetWhere(genericWrapperDispatch)
            .orShould().dependOnClassesThat().areAssignableTo(DataFetcherResult::class.java)
            .because("TypeShape.kt walks a declared type once and unwraps values by the same layers, so a container handled anywhere else " +
                "can disagree with it. Add a ContainerKind or a generic wrapper, or ask TypeShape")
            .check(classes)
    }

    @Test
    fun `only the argument converter converts through a TokenBuffer`() {
        noClasses().that(DescribedPredicate.not(declaredIn("ArgumentConverter.kt")))
            .should().accessTargetWhere(tokenBufferConversion)
            .orShould().dependOnClassesThat().areAssignableTo(TokenBuffer::class.java)
            .because("ArgumentConverter is the one entry point that turns a coerced argument into a parameter's type, with its fallbacks for scalars Jackson can't convert. Use the ResolverPlan's ArgumentConverter")
            .check(classes)
    }

    @Test
    fun `only the resolver plan converts arguments`() {
        noClasses().that(DescribedPredicate.not(declaredIn("ResolverPlan.kt").orOwnerOf(ArgumentConverter::class.java)))
            .should().accessTargetWhere(argumentConversion)
            .because("ResolverPlan builds one ArgumentConverter per argument at schema build, next to the argument's Optional and scalar handling. Read the plan's argument targets instead")
            .check(classes)
    }
}
