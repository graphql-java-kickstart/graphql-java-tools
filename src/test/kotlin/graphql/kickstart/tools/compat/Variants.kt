package graphql.kickstart.tools.compat

import graphql.kickstart.tools.GraphQLResolver
import graphql.kickstart.tools.compat.scenarios.Scenario
import graphql.kickstart.tools.compat.scenarios.parser
import graphql.language.AstPrinter
import graphql.language.DirectiveDefinition
import graphql.language.EnumTypeDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.Type
import graphql.language.TypeDefinition
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
import graphql.parser.Parser
import org.springframework.aop.framework.ProxyFactory

data class CaseKey(val scenario: String, val operator: String, val snippet: String) {
    val seed get() = CaseKey(scenario, "seed", "")

    override fun toString() = listOf(scenario, operator, snippet).filter { it.isNotEmpty() }.joinToString(" | ")
}

class Variant(val key: CaseKey, val build: () -> Unit)

/**
 * The scenario as it is, plus one invalid SDL snippet per grammar rule it can break, plus every resolver behind a JDK proxy.
 * The rules come from the SDL grammar, not from known bugs, so a new rejection of any of them shows up.
 */
fun variants(scenario: Scenario): List<Variant> {
    fun variant(operator: String, snippet: String = "", resolvers: () -> List<GraphQLResolver<*>> = { scenario.resolvers }) =
        Variant(CaseKey(scenario.name, operator, snippet)) {
            val builder = scenario.parser(resolvers())
            if (snippet.isNotEmpty()) builder.schemaString(snippet)
            scenario.schema(builder.build())
        }

    return listOf(variant("seed"), variant("proxy") { scenario.resolvers.map { ProxyFactory(it).proxy as GraphQLResolver<*> } }) +
        snippets(scenario).map { (operator, snippet) -> variant(operator, snippet) }
}

private val KINDS: Map<Class<*>, String> = mapOf(
    ObjectTypeDefinition::class.java to "type",
    InterfaceTypeDefinition::class.java to "interface",
    InputObjectTypeDefinition::class.java to "input",
    EnumTypeDefinition::class.java to "enum",
    UnionTypeDefinition::class.java to "union",
    ScalarTypeDefinition::class.java to "scalar"
)

private val LOCATIONS = mapOf("type" to "OBJECT", "interface" to "INTERFACE", "input" to "INPUT_OBJECT", "enum" to "ENUM", "union" to "UNION", "scalar" to "SCALAR")

private val SPECIFIED_BY = Parser().parseDocument("directive @specifiedBy(url: String!) on SCALAR").definitions.single() as DirectiveDefinition

private fun snippets(scenario: Scenario): Set<Pair<String, String>> {
    val sdl = scenario.files.joinToString("\n") { Scenario::class.java.classLoader.getResource(it)!!.readText() }
    val definitions = Parser().parseDocument(sdl).definitions
    val types = definitions.filterIsInstance<TypeDefinition<*>>().filter { it.javaClass in KINDS }
    val directives = definitions.filterIsInstance<DirectiveDefinition>() + SPECIFIED_BY
    val objectNames = types.filterIsInstance<ObjectTypeDefinition>().map { it.name } - setOf("Query", "Mutation", "Subscription")
    val snippets = LinkedHashSet<Pair<String, String>>()

    for (type in types) {
        val kind = KINDS.getValue(type.javaClass)
        for (other in KINDS.values - kind) {
            when (other) {
                "enum" -> "extend enum ${type.name} { COMPAT_Z }"
                "union" -> objectNames.firstOrNull { it != type.name }?.let { "extend union ${type.name} = $it" }
                "scalar" -> "extend scalar ${type.name} @specifiedBy(url: \"https://example.com\")"
                else -> "extend $other ${type.name} { compatZ: String }"
            }?.let { snippets += "kind" to it }
        }
        snippets += "undeclared" to "extend $kind ${type.name} @compatUndeclared"
        firstMember(type)?.let { snippets += "repeat" to "extend $kind ${type.name} $it" }
        for (directive in directives) {
            if (LOCATIONS.getValue(kind) !in directive.directiveLocations.map { it.name }) {
                snippets += "location" to "extend $kind ${type.name} @${directive.name}"
                continue
            }
            for (argument in directive.inputValueDefinitions) {
                for (literal in wrongLiterals(argument.type)) {
                    snippets += "argval" to "extend $kind ${type.name} @${directive.name}(${argument.name}: $literal)"
                }
            }
            if (!directive.isRepeatable && directive.inputValueDefinitions.none { it.type is NonNullType && it.defaultValue == null }) {
                snippets += "repeat-directive" to "extend $kind ${type.name} @${directive.name} @${directive.name}"
            }
        }
    }
    return snippets
}

private fun firstMember(type: TypeDefinition<*>): String? = when (type) {
    is EnumTypeDefinition -> type.enumValueDefinitions.firstOrNull()?.let { "{ ${it.name} }" }
    is UnionTypeDefinition -> type.memberTypes.firstOrNull()?.let { "= ${(it as TypeName).name}" }
    is ObjectTypeDefinition -> type.fieldDefinitions.firstOrNull()?.let { "{ ${AstPrinter.printAst(it).oneLine()} }" }
    is InterfaceTypeDefinition -> type.fieldDefinitions.firstOrNull()?.let { "{ ${AstPrinter.printAst(it).oneLine()} }" }
    is InputObjectTypeDefinition -> type.inputValueDefinitions.firstOrNull()?.let { "{ ${AstPrinter.printAst(it).oneLine()} }" }
    else -> null
}

private fun String.oneLine() = replace(Regex("\\s+"), " ").trim()

private fun wrongLiterals(type: Type<*>): List<String> {
    val inner = if (type is NonNullType) type.type else type
    val literals = when (if (inner is ListType) "list" else (inner as TypeName).name) {
        "String", "ID" -> listOf("5", "NOPE")
        "Int", "Float", "Boolean" -> listOf("\"x\"")
        "list" -> listOf("{ a: 1 }")
        else -> listOf("5", "\"x\"")
    }
    return if (type is NonNullType) literals + "null" else literals
}
