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

/**
 * [warnNames] is what a release's WARN has to name to count as a warning about this case: one name out of each set. Seed
 * and proxy cases have none, because only their error says what broke, so the WARNs have to name what the error quotes.
 */
class Variant(val key: CaseKey, val warnNames: List<Set<String>>, val build: () -> Unit)

fun variants(scenario: Scenario): List<Variant> {
    fun variant(
        operator: String,
        snippet: String = "",
        warnNames: List<Set<String>> = emptyList(),
        resolvers: () -> List<GraphQLResolver<*>> = { scenario.resolvers }
    ) = Variant(CaseKey(scenario.name, operator, snippet), warnNames) {
        val builder = scenario.parser(resolvers())
        if (snippet.isNotEmpty()) builder.schemaString(snippet)
        scenario.schema(builder.build())
    }

    return listOf(variant("seed"), variant("proxy") { scenario.resolvers.map { ProxyFactory(it).proxy as GraphQLResolver<*> } }) +
        snippets(scenario).map { (key, warnNames) -> variant(key.first, key.second, warnNames) }
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

private fun snippets(scenario: Scenario): Map<Pair<String, String>, List<Set<String>>> {
    val sdl = scenario.files.joinToString("\n") { Scenario::class.java.classLoader.getResource(it)!!.readText() }
    val definitions = Parser().parseDocument(sdl).definitions
    val types = definitions.filterIsInstance<TypeDefinition<*>>().filter { it.javaClass in KINDS }
    val directives = definitions.filterIsInstance<DirectiveDefinition>() + SPECIFIED_BY
    val objectNames = types.filterIsInstance<ObjectTypeDefinition>().map { it.name } - setOf("Query", "Mutation", "Subscription")
    val snippets = LinkedHashMap<Pair<String, String>, List<Set<String>>>()

    for (type in types) {
        val kind = KINDS.getValue(type.javaClass)
        fun add(operator: String, snippet: String, vararg warnNames: Set<String>) = snippets.putIfAbsent(operator to snippet, warnNames.toList())
        // a WARN about a directive in an extension may point at the application instead of naming the type
        fun addDirective(operator: String, directive: String, applications: String) {
            val snippet = "extend $kind ${type.name} $applications"
            val positions = Regex("@$directive\\b").findAll(snippet).map { "line 1, column ${it.range.first + 1}" }
            add(operator, snippet, setOf(directive), setOf(type.name) + positions)
        }

        for (other in KINDS.values - kind) {
            when (other) {
                "enum" -> "extend enum ${type.name} { COMPAT_Z }"
                "union" -> objectNames.firstOrNull { it != type.name }?.let { "extend union ${type.name} = $it" }
                "scalar" -> "extend scalar ${type.name} @specifiedBy(url: \"https://example.com\")"
                else -> "extend $other ${type.name} { compatZ: String }"
            }?.let { add("kind", it, setOf(type.name)) }
        }
        addDirective("undeclared", "compatUndeclared", "@compatUndeclared")
        firstMember(type)?.let { (name, member) -> add("repeat", "extend $kind ${type.name} $member", setOf(type.name), setOf(name)) }
        for (directive in directives) {
            val name = directive.name
            if (LOCATIONS.getValue(kind) !in directive.directiveLocations.map { it.name }) {
                addDirective("location", name, "@$name")
                continue
            }
            for (argument in directive.inputValueDefinitions) {
                for (literal in wrongLiterals(argument.type)) {
                    addDirective("argval", name, "@$name(${argument.name}: $literal)")
                }
            }
            if (!directive.isRepeatable && directive.inputValueDefinitions.none { it.type is NonNullType && it.defaultValue == null }) {
                addDirective("repeat-directive", name, "@$name @$name")
            }
        }
    }
    return snippets
}

private fun firstMember(type: TypeDefinition<*>): Pair<String, String>? = when (type) {
    is EnumTypeDefinition -> type.enumValueDefinitions.firstOrNull()?.let { it.name to "{ ${it.name} }" }
    is UnionTypeDefinition -> type.memberTypes.firstOrNull()?.let { (it as TypeName).name!! to "= ${it.name}" }
    is ObjectTypeDefinition -> type.fieldDefinitions.firstOrNull()?.let { it.name to "{ ${AstPrinter.printAst(it).oneLine()} }" }
    is InterfaceTypeDefinition -> type.fieldDefinitions.firstOrNull()?.let { it.name to "{ ${AstPrinter.printAst(it).oneLine()} }" }
    is InputObjectTypeDefinition -> type.inputValueDefinitions.firstOrNull()?.let { it.name to "{ ${AstPrinter.printAst(it).oneLine()} }" }
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
