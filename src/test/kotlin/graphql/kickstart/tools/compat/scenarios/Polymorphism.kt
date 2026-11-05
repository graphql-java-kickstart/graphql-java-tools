package graphql.kickstart.tools.compat.scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParserBuilder

object Polymorphism : Scenario {
    override val name = "polymorphism"

    override val files = listOf("compat/scenarios/polymorphism/schema.graphqls")
    override val resolvers = listOf(PolymorphismQuery())

    override fun configure(builder: SchemaParserBuilder) = builder
        .dictionary(Novel::class.java)
        .dictionary("Writer", WriterEntity::class.java)
}

interface Entity {
    val id: String
}

data class Novel(override val id: String, val title: String) : Entity

data class WriterEntity(override val id: String, val name: String) : Entity

private val entities: List<Entity> = listOf(Novel("n1", "Solaris"), WriterEntity("w1", "Stanisław Lem"))

class PolymorphismQuery : GraphQLQueryResolver {
    fun node(id: String): Entity? = entities.find { it.id == id }

    fun search(term: String): List<Any> = entities.filter {
        val text = if (it is Novel) it.title else (it as WriterEntity).name
        text.contains(term, ignoreCase = true)
    }
}
