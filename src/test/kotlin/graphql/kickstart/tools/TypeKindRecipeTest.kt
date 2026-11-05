package graphql.kickstart.tools

import graphql.Scalars
import graphql.language.Node
import graphql.schema.*
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.reflect.KClass

class TypeKindRecipeTest {

    private val kinds: Map<KClass<out MergedType<*>>, Kind> = mapOf(
        MergedType.Object::class to Kind("Item", "ObjectTypeDefinition", Repeat("extend type Item { id: ID! }", "field", "id")),
        MergedType.Interface::class to Kind("Node", "InterfaceTypeDefinition", Repeat("extend interface Node { id: ID! }", "field", "id")),
        MergedType.Union::class to Kind("Result", "UnionTypeDefinition", Repeat("extend union Result = Item", "member", "Item")),
        MergedType.Enum::class to Kind("Color", "EnumTypeDefinition", Repeat("extend enum Color { RED }", "value", "RED")),
        MergedType.Input::class to Kind("Filter", "InputObjectTypeDefinition", Repeat("extend input Filter { id: ID }", "field", "id")),
        MergedType.Scalar::class to Kind("Date", "ScalarTypeDefinition", repeat = null),
    )

    @Test
    fun `every kind of type has a case`() {
        fun leaves(kind: KClass<out MergedType<*>>): List<KClass<out MergedType<*>>> =
            if (kind.isSealed) kind.sealedSubclasses.flatMap(::leaves) else listOf(kind)

        assertEquals(kinds.keys, leaves(MergedType::class).toSet())
    }

    @Test
    fun `every kind of type gets the description, definitions and directives of its definition and extension`() {
        val schema = parser(sdl).makeExecutableSchema().assertEveryFieldResolves()

        val built = kinds.values.map { kind ->
            val type = schema.getType(kind.name) as GraphQLDirectiveContainer
            listOf(type.description, type.definition?.javaClass?.simpleName, (type as GraphQLNamedType).extensionDefinitions().size, type.appliedDirectives.map { it.name })
        }
        assertEquals(built, kinds.values.map { kind ->
            listOf("${kind.name} description", kind.definitionClass, 1, listOf("a", "b"))
        })
    }

    @Test
    fun `every kind of type with members rejects a member that its extension repeats`() {
        val repeating = kinds.values.filter { it.repeat != null }
        val errors = repeating.map { kind ->
            assertThrows(SchemaError::class.java) { parser(sdl + kind.repeat!!.extension).makeExecutableSchema() }.message
        }

        val appendedExtensionLine = sdl.lines().size
        assertEquals(errors, repeating.map { kind ->
            val repeat = kind.repeat!!
            val column = repeat.extension.indexOf(repeat.member, repeat.extension.indexOf(kind.name) + kind.name.length) + 1
            "Type '${kind.name}' declares ${repeat.noun} '${repeat.member}' more than once at line $appendedExtensionLine, column $column."
        })
    }

    private class Kind(val name: String, val definitionClass: String, val repeat: Repeat?)

    private class Repeat(val extension: String, val noun: String, val member: String)

    private val sdl = """
        |directive @a on OBJECT | INTERFACE | UNION | ENUM | INPUT_OBJECT | SCALAR
        |directive @b on OBJECT | INTERFACE | UNION | ENUM | INPUT_OBJECT | SCALAR
        |
        |type Query { node: Node result: Result color: Color search(filter: Filter): String today: Date }
        |
        |"Item description"
        |type Item implements Node @a { id: ID! }
        |extend type Item @b { name: String }
        |type Other { id: ID! }
        |
        |"Node description"
        |interface Node @a { id: ID! }
        |extend interface Node @b { name: String }
        |
        |"Result description"
        |union Result @a = Item
        |extend union Result @b = Other
        |
        |"Color description"
        |enum Color @a { RED }
        |extend enum Color @b { GREEN }
        |
        |"Filter description"
        |input Filter @a { id: ID }
        |extend input Filter @b { limit: Int }
        |
        |"Date description"
        |scalar Date @a
        |extend scalar Date @b
        |""".trimMargin()

    private val date = GraphQLScalarType.newScalar().name("Date").description("provided").coercing(Scalars.GraphQLString.coercing).build()

    private fun parser(schema: String) = SchemaParser.newParser()
        .schemaString(schema)
        .resolvers(Query())
        .dictionary(Item::class, Other::class)
        .scalars(date)
        .build()

    private fun GraphQLNamedType.extensionDefinitions(): List<Node<*>> = when (this) {
        is GraphQLObjectType -> extensionDefinitions
        is GraphQLInterfaceType -> extensionDefinitions
        is GraphQLUnionType -> extensionDefinitions
        is GraphQLEnumType -> extensionDefinitions
        is GraphQLInputObjectType -> extensionDefinitions
        is GraphQLScalarType -> extensionDefinitions
        else -> error("not a named type with extensions: $name")
    }

    class Query : GraphQLQueryResolver {
        fun node(): Entity = Item("1", "item")
        fun result(): Outcome = Other("2")
        fun color() = Color.GREEN
        fun search(filter: Filter) = "${filter.id} ${filter.limit}"
        fun today() = "2026-11-04"
    }

    interface Entity {
        val id: String
    }

    interface Outcome

    class Item(override val id: String, val name: String?) : Entity, Outcome

    class Other(val id: String) : Outcome

    enum class Color { RED, GREEN }

    class Filter(val id: String?, val limit: Int?)
}
