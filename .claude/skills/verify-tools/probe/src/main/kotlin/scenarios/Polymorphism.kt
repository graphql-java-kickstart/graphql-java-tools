package scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser

/**
 * Interface and union fields whose resolvers return a supertype, so the concrete classes are only known through the
 * dictionary. `Writer` is backed by a class with a different name.
 */
object Polymorphism : Scenario {
    override val name = "polymorphism"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/polymorphism/schema.graphqls")
        .resolvers(PolymorphismQuery())
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
