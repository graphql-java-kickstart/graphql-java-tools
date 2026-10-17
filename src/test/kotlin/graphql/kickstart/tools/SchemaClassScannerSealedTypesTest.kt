package graphql.kickstart.tools

import graphql.GraphQL
import graphql.schema.GraphQLUnionType
import org.junit.Assert.assertThrows
import org.junit.Test

class SchemaClassScannerSealedTypesTest {

    private val petSchema = """
        type Query {
            pets: [Pet]
        }

        union Pet = NormalPet | WeirdPet

        type NormalPet {
            name: String
        }

        type WeirdPet {
            classification: String
        }
        """

    private val petsQuery = """
        query {
            pets {
                __typename
                ... on NormalPet { name }
                ... on WeirdPet { classification }
            }
        }
        """

    private val pets = listOf(
        mapOf("__typename" to "NormalPet", "name" to "Rex"),
        mapOf("__typename" to "WeirdPet", "classification" to "axolotl")
    )

    @Test
    fun `scanner finds members of unions among the subclasses of sealed classes`() {
        val schema = SchemaParser.newParser()
            .schemaString(petSchema)
            .resolvers(SealedClassUnion())
            .build()
            .makeExecutableSchema()

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) { petsQuery }

        assertEquals(data["pets"], pets)
    }

    class SealedClassUnion : GraphQLQueryResolver {
        fun pets(): List<Pet> = listOf(Pet.NormalPet("Rex"), Pet.Exotic.WeirdPet("axolotl"))

        sealed class Pet {
            class NormalPet(val name: String?) : Pet()

            sealed class Exotic : Pet() {
                class WeirdPet(val classification: String?) : Exotic()
            }
        }
    }

    @Test
    fun `scanner finds implementors of interfaces among the subclasses of sealed interfaces`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    animals: [Animal]
                }

                interface Animal {
                    name: String
                }

                type Dog implements Animal {
                    name: String
                    barks: Boolean
                }

                type Cat implements Animal {
                    name: String
                    lives: Int
                }
                """)
            .resolvers(SealedInterfaceImplementors())
            .build()
            .makeExecutableSchema()

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                animals {
                    __typename
                    name
                    ... on Dog { barks }
                    ... on Cat { lives }
                }
            }
            """
        }

        assertEquals(data["animals"], listOf(
            mapOf("__typename" to "Dog", "name" to "Rex", "barks" to true),
            mapOf("__typename" to "Cat", "name" to "Tom", "lives" to 9)
        ))
    }

    class SealedInterfaceImplementors : GraphQLQueryResolver {
        fun animals(): List<Animal> = listOf(Dog("Rex", true), Cat("Tom", 9))

        sealed interface Animal {
            val name: String?
        }

        sealed class Mammal : Animal

        // a subclass of both Animal and Mammal, so it's found twice among the subclasses of Animal
        class Dog(override val name: String?, val barks: Boolean?) : Mammal(), Animal

        class Cat(override val name: String?, val lives: Int?) : Animal
    }

    @Test
    fun `scanner passes the type arguments of generic sealed classes on to their subclasses`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    results: [PetResult]
                }

                union PetResult = Success | Failure

                type Success {
                    value: Pet
                }

                type Failure {
                    message: String
                }

                type Pet {
                    name: String
                }
                """)
            .resolvers(GenericSealedClass())
            .build()
            .makeExecutableSchema()

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                results {
                    __typename
                    ... on Success { value { name } }
                    ... on Failure { message }
                }
            }
            """
        }

        assertEquals(data["results"], listOf(
            mapOf("__typename" to "Success", "value" to mapOf("name" to "Rex")),
            mapOf("__typename" to "Failure", "message" to "Not found")
        ))
    }

    class GenericSealedClass : GraphQLQueryResolver {
        fun results(): List<Result<Pet>> = listOf(Result.Success(Pet("Rex")), Result.Failure("Not found"))

        class Pet(val name: String?)

        sealed class Result<out T> {
            class Success<T>(val value: T) : Result<T>()

            class Failure(val message: String?) : Result<Nothing>()
        }
    }

    @Test
    fun `scanner scans the subclasses of sealed classes like any other class`() {
        val searchSchema = """
            type Query {
                results: [SearchResult]
            }

            union SearchResult = Book | Author

            type Book {
                title: String
                author: Author
            }

            type Author {
                name: String
            }
            """

        val schema = SchemaParser.newParser()
            .schemaString(searchSchema)
            .resolvers(MemberReachableThroughSealedSubclass())
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("SearchResult") as GraphQLUnionType).types.map { it.name }.toSet(), setOf("Book", "Author"))

        val error = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(searchSchema)
                .resolvers(ConflictingSealedSubclass())
                .build()
                .makeExecutableSchema()
        }

        val message = error.message.orEmpty()
        assert(message.startsWith("Two different classes used for type Author:\n- ${ConflictingSealedSubclass.SearchResult.Author::class.java.name}:\n|   subclass of sealed type ${ConflictingSealedSubclass.SearchResult::class.java.name}")) { message }
        assert(message.contains("- ${ConflictingSealedSubclass.AuthorDto::class.java.name}:")) { message }
    }

    // Author isn't a subclass of SearchResult, so it's only found by scanning Book
    class MemberReachableThroughSealedSubclass : GraphQLQueryResolver {
        fun results(): List<SearchResult> = listOf()

        sealed class SearchResult {
            class Book(val title: String?, val author: Author?) : SearchResult()
        }

        class Author(val name: String?)
    }

    // scanning Book finds another class for Author than its sealed subclass
    class ConflictingSealedSubclass : GraphQLQueryResolver {
        fun results(): List<SearchResult> = listOf()

        sealed class SearchResult {
            class Book(val title: String?, val author: AuthorDto?) : SearchResult()

            class Author(val name: String?) : SearchResult()
        }

        class AuthorDto(val name: String?)
    }

    @Test
    fun `scanner prefers the dictionary over the subclasses of sealed classes`() {
        val schema = SchemaParser.newParser()
            .schemaString(petSchema)
            .resolvers(DictionaryOverridesSealedSubclass())
            .dictionary("WeirdPet", DictionaryOverridesSealedSubclass.ClassifiedWeirdPet::class)
            .build()
            .makeExecutableSchema()

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) { petsQuery }

        assertEquals(data["pets"], pets)
    }

    class DictionaryOverridesSealedSubclass : GraphQLQueryResolver {
        fun pets(): List<Pet> = listOf(Pet.NormalPet("Rex"), ClassifiedWeirdPet("axolotl"))

        sealed class Pet {
            class NormalPet(val name: String?) : Pet()

            // no classification, so the build fails if this is scanned instead of the class in the dictionary
            open class WeirdPet : Pet()
        }

        class ClassifiedWeirdPet(val classification: String?) : Pet.WeirdPet()
    }

    @Test
    fun `scanner still requires classes for members of unions without a single sealed subclass named after them`() {
        fun buildError(resolver: GraphQLQueryResolver) = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(petSchema)
                .resolvers(resolver)
                .build()
                .makeExecutableSchema()
        }.message

        assertEquals(buildError(NonSealedInterfaceUnion()), "Object type 'NormalPet' is a member of a known union, but no class could be found for that type name.  Please pass a class for type 'NormalPet' in the parser's dictionary.")

        assertEquals(buildError(PartlySealedUnion()), "Object type 'WeirdPet' is a member of a known union, but no class could be found for that type name.  Please pass a class for type 'WeirdPet' in the parser's dictionary." +
            "  No subclass of sealed type ${PartlySealedUnion.Pet::class.java.name} is named 'WeirdPet'.")

        assertEquals(buildError(AmbiguousSealedSubclassNames()), "Object type 'NormalPet' is a member of a known union, but no class could be found for that type name.  Please pass a class for type 'NormalPet' in the parser's dictionary." +
            "  More than one subclass of sealed type ${AmbiguousSealedSubclassNames.Pet::class.java.name} is named 'NormalPet': " +
            "${AmbiguousSealedSubclassNames.Pet.Cat.NormalPet::class.java.name}, ${AmbiguousSealedSubclassNames.Pet.Dog.NormalPet::class.java.name}.")
    }

    class NonSealedInterfaceUnion : GraphQLQueryResolver {
        fun pets(): List<Pet> = listOf()

        interface Pet

        class NormalPet(val name: String?) : Pet

        class WeirdPet(val classification: String?) : Pet
    }

    class PartlySealedUnion : GraphQLQueryResolver {
        fun pets(): List<Pet> = listOf()

        sealed class Pet {
            class NormalPet(val name: String?) : Pet()
        }
    }

    class AmbiguousSealedSubclassNames : GraphQLQueryResolver {
        fun pets(): List<Pet> = listOf()

        sealed class Pet {
            sealed class Cat : Pet() {
                class NormalPet(val name: String?) : Cat()
            }

            sealed class Dog : Pet() {
                class NormalPet(val name: String?) : Dog()
            }
        }
    }
}
