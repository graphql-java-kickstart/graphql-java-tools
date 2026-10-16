package graphql.kickstart.tools

import graphql.GraphQL
import graphql.schema.GraphQLInterfaceType
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

    @Test
    fun `scanner finds members of unions among the subclasses of sealed classes`() {
        val schema = SchemaParser.newParser()
            .schemaString(petSchema)
            .resolvers(SealedClassUnion())
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("Pet") as GraphQLUnionType).types.map { it.name }.toSet(), setOf("NormalPet", "WeirdPet"))

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) { petsQuery }

        assertEquals(data["pets"], listOf(
            mapOf("__typename" to "NormalPet", "name" to "Rex"),
            mapOf("__typename" to "WeirdPet", "classification" to "axolotl")
        ))
    }

    class SealedClassUnion : GraphQLQueryResolver {
        fun pets(): List<Pet> = listOf(Pet.NormalPet("Rex"), Pet.WeirdPet("axolotl"))

        sealed class Pet {
            class NormalPet(val name: String?) : Pet()

            class WeirdPet(val classification: String?) : Pet()
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

        assertEquals(schema.getImplementations(schema.getType("Animal") as GraphQLInterfaceType)?.map { it.name }?.toSet(), setOf("Dog", "Cat"))

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

        class Dog(override val name: String?, val barks: Boolean?) : Animal

        class Cat(override val name: String?, val lives: Int?) : Animal
    }

    @Test
    fun `scanner finds members of unions among the subclasses of nested sealed classes`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    pets: [Pet]
                }

                union Pet = Dog | Fish

                type Dog {
                    name: String
                }

                type Fish {
                    fins: Int
                }
                """)
            .resolvers(NestedSealedClasses())
            .build()
            .makeExecutableSchema()

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                pets {
                    __typename
                    ... on Dog { name }
                    ... on Fish { fins }
                }
            }
            """
        }

        assertEquals(data["pets"], listOf(
            mapOf("__typename" to "Dog", "name" to "Rex"),
            mapOf("__typename" to "Fish", "fins" to 2)
        ))
    }

    class NestedSealedClasses : GraphQLQueryResolver {
        fun pets(): List<Pet> = listOf(Pet.Mammal.Dog("Rex"), Pet.Fish(2))

        sealed class Pet {
            sealed class Mammal : Pet() {
                class Dog(val name: String?) : Mammal()
            }

            class Fish(val fins: Int?) : Pet()
        }
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
    fun `scanner finds the other members of unions by scanning the subclasses of sealed classes`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    results: [SearchResult]
                }

                union SearchResult = Book | Review

                type Book {
                    title: String
                    review: Review
                }

                type Review {
                    text: String
                }
                """)
            .resolvers(MemberReachableThroughSealedSubclass())
            .build()
            .makeExecutableSchema()

        assertEquals((schema.getType("SearchResult") as GraphQLUnionType).types.map { it.name }.toSet(), setOf("Book", "Review"))
    }

    class MemberReachableThroughSealedSubclass : GraphQLQueryResolver {
        fun results(): List<SearchResult> = listOf()

        sealed class SearchResult {
            class Book(val title: String?, val review: Review?) : SearchResult()
        }

        class Review(val text: String?)
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

        assertEquals(data["pets"], listOf(
            mapOf("__typename" to "NormalPet", "name" to "Rex"),
            mapOf("__typename" to "WeirdPet", "classification" to "axolotl")
        ))
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
    fun `scanner reports a class found for a member that differs from its sealed subclass`() {
        val error = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
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
                    """)
                .resolvers(ConflictingSealedSubclass())
                .build()
                .makeExecutableSchema()
        }

        val message = error.message.orEmpty()
        assert(message.startsWith("Two different classes used for type Author:\n- ${ConflictingSealedSubclass.SearchResult.Author::class.java.name}:\n|   subclass of sealed type ${ConflictingSealedSubclass.SearchResult::class.java.name}")) { message }
        assert(message.contains("- ${ConflictingSealedSubclass.AuthorDto::class.java.name}:")) { message }
    }

    class ConflictingSealedSubclass : GraphQLQueryResolver {
        fun results(): List<SearchResult> = listOf()

        sealed class SearchResult {
            class Book(val title: String?, val author: AuthorDto?) : SearchResult()

            class Author(val name: String?) : SearchResult()
        }

        class AuthorDto(val name: String?)
    }

    @Test
    fun `scanner still requires classes for members of unions whose class isn't sealed`() {
        val error = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(petSchema)
                .resolvers(NonSealedInterfaceUnion())
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Object type 'NormalPet' is a member of a known union, but no class could be found for that type name.  Please pass a class for type 'NormalPet' in the parser's dictionary.")
    }

    class NonSealedInterfaceUnion : GraphQLQueryResolver {
        fun pets(): List<Pet> = listOf()

        interface Pet

        class NormalPet(val name: String?) : Pet

        class WeirdPet(val classification: String?) : Pet
    }

    @Test
    fun `scanner still requires classes for members of unions that no subclass of the sealed class is named after`() {
        val error = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(petSchema)
                .resolvers(PartlySealedUnion())
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Object type 'WeirdPet' is a member of a known union, but no class could be found for that type name.  Please pass a class for type 'WeirdPet' in the parser's dictionary." +
            "  No subclass of sealed type ${PartlySealedUnion.Pet::class.java.name} is named 'WeirdPet'.")
    }

    class PartlySealedUnion : GraphQLQueryResolver {
        fun pets(): List<Pet> = listOf()

        sealed class Pet {
            class NormalPet(val name: String?) : Pet()
        }
    }

    @Test
    fun `scanner explains that more than one subclass of the sealed class is named after a member`() {
        val error = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        pets: [Pet]
                    }

                    union Pet = Baby

                    type Baby {
                        name: String
                    }
                    """)
                .resolvers(AmbiguousSealedSubclassNames())
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, "Object type 'Baby' is a member of a known union, but no class could be found for that type name.  Please pass a class for type 'Baby' in the parser's dictionary." +
            "  More than one subclass of sealed type ${AmbiguousSealedSubclassNames.Pet::class.java.name} is named 'Baby': " +
            "${AmbiguousSealedSubclassNames.Pet.Cat.Baby::class.java.name}, ${AmbiguousSealedSubclassNames.Pet.Dog.Baby::class.java.name}.")
    }

    class AmbiguousSealedSubclassNames : GraphQLQueryResolver {
        fun pets(): List<Pet> = listOf()

        sealed class Pet {
            sealed class Cat : Pet() {
                class Baby(val name: String?) : Cat()
            }

            sealed class Dog : Pet() {
                class Baby(val name: String?) : Dog()
            }
        }
    }
}
