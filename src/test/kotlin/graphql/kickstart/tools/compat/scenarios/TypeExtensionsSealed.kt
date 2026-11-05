package graphql.kickstart.tools.compat.scenarios

import graphql.kickstart.tools.GraphQLQueryResolver

object TypeExtensionsSealed : Scenario {
    override val name = "type-extensions-sealed"

    override val files = listOf("compat/scenarios/type-extensions-sealed/schema.graphqls")
    override val resolvers = listOf(TypeExtensionsSealedQuery())
}

sealed class Pet {
    data class Dog(val name: String) : Pet()

    data class Cat(val lives: Int) : Pet()
}

sealed interface Animal {
    val name: String
}

data class Cow(override val name: String) : Animal

data class Horse(override val name: String) : Animal

class TypeExtensionsSealedQuery : GraphQLQueryResolver {
    fun pets(): List<Pet> = listOf(Pet.Dog("Rex"), Pet.Cat(9))

    fun animals(): List<Animal> = listOf(Cow("Daisy"), Horse("Spirit"))
}
