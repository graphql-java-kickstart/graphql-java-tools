package scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser

/**
 * Extensions of every kind of type: fields, values, members, interfaces and directives merged into the type, repeats that
 * keep the first one with a warning, extensions without a type, and an extension of a different kind than its type.
 */
object TypeExtensions : Scenario {
    override val name = "type-extensions"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/type-extensions/schema.graphqls")
        .resolvers(TypeExtensionsQuery())
        .dictionary(Article::class.java, Video::class.java)
}

interface Node {
    val id: String
}

data class Article(override val id: String, val createdAt: String?, val name: String?, val title: String) : Node

data class Video(override val id: String, val createdAt: String?, val name: String?, val seconds: Int) : Node

enum class Color { RED, BLUE, GREEN }

enum class Shade { LIGHT, DARK }

data class Filter(val term: String?, val limit: Int?)

private val nodes: List<Node> = listOf(Article("a1", "2026-10-01", "Launch", "We launched"), Video("v1", "2026-10-02", "Demo", 90))

class TypeExtensionsQuery : GraphQLQueryResolver {
    fun node(id: String): Node? = nodes.find { it.id == id }

    fun search(): List<Any> = nodes

    fun colors(): List<Color> = Color.entries

    fun shades(): List<Shade> = Shade.entries

    fun count(filter: Filter): Int = nodes.count { (it as? Article)?.title?.contains(filter.term.orEmpty()) ?: true }.coerceAtMost(filter.limit ?: Int.MAX_VALUE)
}
