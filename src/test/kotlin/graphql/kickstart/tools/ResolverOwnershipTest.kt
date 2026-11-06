package graphql.kickstart.tools

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.base.DescribedPredicate.and
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.conditions.ArchConditions.be
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import graphql.execution.DataFetcherResult
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper
import graphql.kickstart.tools.resolver.ArgumentConverter
import graphql.kickstart.tools.resolver.ResolverDataFetcher
import graphql.kickstart.tools.resolver.ResolverPlan
import graphql.kickstart.tools.resolver.TypeShape
import graphql.schema.DataFetcher
import org.apache.commons.lang3.reflect.FieldUtils
import org.apache.commons.lang3.reflect.MethodUtils
import org.junit.Test
import tools.jackson.core.JsonParser
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.ObjectWriter
import tools.jackson.databind.util.TokenBuffer
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.nio.file.Paths

class ResolverOwnershipTest {

    companion object {
        private val classes: JavaClasses = ClassFileImporter()
            .importPath(Paths.get(ResolverPlan::class.java.protectionDomain.codeSource.location.toURI()))

        private val resolverPackage = ResolverPlan::class.java.packageName
        private val MEMBER = "$resolverPackage.Member"

        private val resolverPlanFile = OwningFile(
            "ResolverPlan.kt", "ResolverPlan", "ResolverPlanKt", "ResolverKind", "ExtraParameter", "FieldResolverError", "Member", "MethodMember",
            "PropertyMember", "MapMember", "ResolverDataFetcher", "MethodFieldResolverDataFetcher", "LightMethodFieldResolverDataFetcher",
            "PropertyFieldResolverDataFetcher", "MapFieldResolverDataFetcher"
        )
        private val typeShapeFile = OwningFile(
            "TypeShape.kt", "TypeShape", "TypeShapeKt", "Position", "Walk", "GenericWrappers", "CompareGenericWrappers", "Layer", "ContainerKind", "Step",
            "Await", "Peel", "EachElement", "DataOf", "ValueOf", "Awaitable"
        )
        private val argumentConverterFile = OwningFile("ArgumentConverter.kt", "ArgumentConverter", "ArgumentConverterKt")
        private val owningFiles = listOf(resolverPlanFile, typeShapeFile, argumentConverterFile)
    }

    private class OwningFile(val fileName: String, vararg topLevelClasses: String) :
        DescribedPredicate<JavaClass>("are declared in $fileName") {
        val names = topLevelClasses.map { "${ResolverPlan::class.java.packageName}.$it" }.toSet()

        override fun test(javaClass: JavaClass) = javaClass.topLevelClass().name in names
    }

    private class Calls(description: String, private val targets: Map<String, Set<String>>) : DescribedPredicate<JavaAccess<*>>(description) {
        override fun test(access: JavaAccess<*>) =
            targets.any { (owner, names) -> access.target.name in names && access.target.owner.isAssignableTo(owner) }
    }

    // KCallable only from kotlin.reflect itself, since Kotlin compiles a callable reference such as Type::name into a class implementing it
    private val kotlinReflection = resideInAnyPackage("kotlin.reflect.full", "kotlin.reflect.jvm")
        .or(resideInAPackage("kotlin.reflect").and(assignableTo("kotlin.reflect.KCallable")))
        .`as`("are kotlin-reflect's member lookups and JVM mappings, or a KFunction, a KProperty or their getters and setters")

    private val memberReflection = Calls(
        "reads, invokes or opens a Java or Kotlin member by reflection",
        mapOf(
            Method::class.java.name to setOf("invoke"),
            Field::class.java.name to setOf(
                "get", "getBoolean", "getByte", "getChar", "getShort", "getInt", "getLong", "getFloat", "getDouble", "getType", "getGenericType",
                "getAnnotatedType", "set", "setBoolean", "setByte", "setChar", "setShort", "setInt", "setLong", "setFloat", "setDouble"
            ),
            Executable::class.java.name to setOf(
                "getGenericParameterTypes", "getGenericReturnType", "getParameterTypes", "getParameterCount", "getAnnotatedReturnType",
                "getAnnotatedParameterTypes"
            ),
            Parameter::class.java.name to setOf("getType", "getParameterizedType", "getAnnotatedType"),
            AccessibleObject::class.java.name to setOf("setAccessible", "trySetAccessible"),
            MethodHandles::class.java.name to setOf("privateLookupIn"),
            MethodHandles.Lookup::class.java.name to setOf(
                "unreflect", "unreflectGetter", "unreflectSetter", "unreflectSpecial", "unreflectVarHandle", "findVirtual", "findStatic", "findSpecial",
                "findGetter", "findSetter", "findStaticGetter", "findStaticSetter", "findVarHandle", "findStaticVarHandle"
            ),
            MethodHandle::class.java.name to setOf("invoke", "invokeExact", "invokeWithArguments"),
            "kotlin.reflect.KClass" to setOf("getMembers", "getConstructors"),
            FieldUtils::class.java.name to setOf(
                "getAllFields", "getAllFieldsList", "getField", "getDeclaredField", "readField", "readDeclaredField", "readStaticField",
                "readDeclaredStaticField", "writeField", "writeDeclaredField", "writeStaticField", "writeDeclaredStaticField"
            ),
            MethodUtils::class.java.name to setOf("invokeMethod", "invokeExactMethod", "invokeStaticMethod", "invokeExactStaticMethod"),
        )
    )

    private val readsInputFieldTypes = object : DescribedPredicate<JavaAccess<*>>(
        "is ScanDictionary.findInputValueTypeInType reading an input object's getter or field type, which it needs before any ResolverPlan exists"
    ) {
        private val reads = Calls(
            "reads a member's declared type",
            mapOf(Executable::class.java.name to setOf("getGenericReturnType", "getParameterCount"), Field::class.java.name to setOf("getGenericType"))
        )

        private val exempt = "${ScanDictionary::class.java.name}.findInputValueTypeInType(${String::class.java.name}, ${Class::class.java.name})"

        override fun test(access: JavaAccess<*>) = access.origin.fullName == exempt && reads.test(access)
    }

    private val tokenBufferConversion = Calls(
        "converts a value through a Jackson TokenBuffer",
        mapOf(
            TokenBuffer::class.java.name to setOf("<init>", "forInputParsing", "forGeneration", "asParser"),
            ObjectWriter::class.java.name to setOf("writeValueIntoBuffer"),
            ObjectMapper::class.java.name to setOf("readValue", "convertValue", "updateValue", "treeToValue", "valueToTree"),
            ObjectReader::class.java.name to setOf("readValue", "readValues", "treeToValue"),
            JsonParser::class.java.name to setOf("readValueAs", "readValueAsTree"),
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

    private val buildsAnUnwrapper = Calls("builds a TypeShape.Unwrapper", mapOf(TypeShape.Unwrapper::class.java.name to setOf("<init>")))

    @Test
    fun `each owning file lists exactly the top-level classes it declares`() {
        val misplaced = owningFiles.flatMap { file -> file.names.filter { !classes.contain(it) || classes.get(it).sourceFileName() != file.fileName } }
        assert(misplaced.isEmpty()) {
            "ResolverOwnershipTest lists classes that weren't compiled from their OwningFile: $misplaced. Move, remove or rename them in its OwningFile lists"
        }

        val unlisted = owningFiles.flatMap { file ->
            classes.filter { it.packageName == resolverPackage && it.isTopLevelClass && it.sourceFileName() == file.fileName }
                .map { it.name }
                .filter { it !in file.names }
        }
        assert(unlisted.isEmpty()) {
            "These classes were compiled from a file named like an owning file but aren't in its OwningFile list in ResolverOwnershipTest: $unlisted. " +
                "Add a class declared in that file to its list. Rename any other file, since only one file in $resolverPackage owns each name"
        }
    }

    @Test
    fun `only the resolver plan reflects on resolver members`() {
        noClasses().that(not(resolverPlanFile))
            .should().accessTargetWhere(and(memberReflection, not(readsInputFieldTypes)))
            .orShould().dependOnClassesThat(kotlinReflection)
            .because("ResolverPlan.kt reads each field's member once, at schema build, and is the only place that invokes it. " +
                "Read what you need from the ResolverPlan, such as its argument targets or type shapes, or add it there")
            .check(classes)
    }

    @Test
    fun `member variants never build a data fetcher`() {
        noClasses().that().areAssignableTo(MEMBER)
            .should().dependOnClassesThat().areAssignableTo(DataFetcher::class.java)
            .orShould().dependOnClassesThat().areAssignableTo(TypeShape.Unwrapper::class.java)
            .because("ResolverPlan builds every data fetcher for a member and unwraps its value with TypeShape.Unwrapper, " +
                "so a member that builds its own fetcher skips the unwrapping. Give the member what its fetcher needs and let ResolverPlan.dataFetcher build it")
            .check(classes)
    }

    @Test
    fun `every library data fetcher is a ResolverDataFetcher in ResolverPlan kt`() {
        classes().that().implement(DataFetcher::class.java)
            .should().beAssignableTo(ResolverDataFetcher::class.java)
            .andShould(be(resolverPlanFile.`as`("declared in ${resolverPlanFile.fileName}")))
            .because("ResolverDataFetcher's constructor takes the TypeShape.Unwrapper the scan matched, so a resolver's value reaches graphql-java " +
                "fully unwrapped. Add the member to ResolverPlan and its fetcher as a ResolverDataFetcher in ResolverPlan.kt instead of a new fetcher")
            .check(classes)
    }

    @Test
    fun `only the resolver plan builds an unwrapper`() {
        noClasses().that(not(resolverPlanFile.or(typeShapeFile)))
            .should().accessTargetWhere(buildsAnUnwrapper)
            .because("ResolverPlan.dataFetcher builds one unwrapper per field from the type shape the scan matched")
            .check(classes)
    }

    @Test
    fun `only TypeShape dispatches on containers`() {
        noClasses().that(not(typeShapeFile))
            .should().accessTargetWhere(genericWrapperDispatch)
            .orShould().dependOnClassesThat().areAssignableTo(DataFetcherResult::class.java)
            .because("TypeShape.kt walks a declared type once and unwraps values by the same layers, so a container handled anywhere else " +
                "can disagree with it. Add a ContainerKind or a generic wrapper, or ask TypeShape")
            .check(classes)
    }

    @Test
    fun `only the argument converter converts through a TokenBuffer`() {
        noClasses().that(not(argumentConverterFile))
            .should().accessTargetWhere(tokenBufferConversion)
            .orShould().dependOnClassesThat().areAssignableTo(TokenBuffer::class.java)
            .because("ArgumentConverter is the one entry point that turns a coerced argument into a parameter's type, with its fallbacks for scalars Jackson can't convert. Use the ResolverPlan's ArgumentConverter")
            .check(classes)
    }

    @Test
    fun `only the resolver plan converts arguments`() {
        noClasses().that(not(resolverPlanFile.or(argumentConverterFile)))
            .should().accessTargetWhere(argumentConversion)
            .because("ResolverPlan builds one ArgumentConverter per argument at schema build, next to the argument's Optional and scalar handling. Read the plan's argument targets instead")
            .check(classes)
    }
}

private fun JavaClass.topLevelClass(): JavaClass = generateSequence(this) { it.enclosingClass.orElse(null) }.last()

private fun JavaClass.sourceFileName(): String? = source.flatMap { it.fileName }.orElse(null)
