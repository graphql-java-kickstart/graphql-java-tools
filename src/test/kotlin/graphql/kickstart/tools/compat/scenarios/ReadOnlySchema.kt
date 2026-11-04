package graphql.kickstart.tools.compat.scenarios

import graphql.kickstart.tools.GraphQLMutationResolver
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser
import graphql.kickstart.tools.SchemaParserBuilder

object ReadOnlySchema : Scenario {
    override val name = "read-only-schema"

    override val files = listOf("compat/scenarios/read-only-schema/schema.graphqls")
    override val resolvers = listOf(ReadOnlyQuery(), ReadOnlyMutation())

    override fun configure(builder: SchemaParserBuilder) = builder
        .dictionary("Dog", ShelterDog::class.java)
        .dictionary("Cat", ShelterCat::class.java)

    override fun schema(parser: SchemaParser) = parser.parseSchemaObjects().toReadOnlySchema()
}

interface ShelterAnimal {
    val name: String
}

data class ShelterDog(override val name: String, val barks: Boolean) : ShelterAnimal

data class ShelterCat(override val name: String, val lives: Int) : ShelterAnimal

private val animals: List<ShelterAnimal> = listOf(ShelterDog("Rex", true), ShelterCat("Tom", 9))

class ReadOnlyQuery : GraphQLQueryResolver {
    fun animal(name: String): ShelterAnimal? = animals.find { it.name == name }

    fun pets(): List<Any> = animals
}

class ReadOnlyMutation : GraphQLMutationResolver {
    fun adopt(name: String): ShelterAnimal = ShelterDog(name, false)
}
