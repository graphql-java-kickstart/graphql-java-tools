package scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser

/**
 * A union member added by `extend union` and an interface implementor added by `extend type X implements Y`, with no
 * dictionary. Their classes can only be found among the subclasses of the sealed class or interface the fields return.
 */
object TypeExtensionsSealed : Scenario {
    override val name = "type-extensions-sealed"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/type-extensions-sealed/schema.graphqls")
        .resolvers(TypeExtensionsSealedQuery())
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
