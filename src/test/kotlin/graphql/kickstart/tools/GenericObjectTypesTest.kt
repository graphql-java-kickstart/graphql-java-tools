package graphql.kickstart.tools

import graphql.GraphQL
import org.junit.Test

class GenericObjectTypesTest {

    @Test
    fun `generic type arguments that are lists are not erased`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    test: TestClass!
                }

                type TestClass {
                    match: StringListEntityMatch!
                    single: StringEntityMatch!
                }

                type StringListEntityMatch {
                    text: String!
                    value: [String!]
                }

                type StringEntityMatch {
                    text: String!
                    value: String
                }
                """)
            .resolvers(EntityMatchQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                test {
                    match { text value }
                    single { text value }
                }
            }
            """
        }

        assertEquals(data["test"], mapOf(
            "match" to mapOf("text" to "x y", "value" to listOf("x", "y")),
            "single" to mapOf("text" to "z", "value" to "z")
        ))
    }

    @Test
    fun `nested generic type arguments are resolved`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    characterHit: CharacterHit!
                    characterHitPage: CharacterHitPage!
                }

                type CharacterHitPage {
                    content: [CharacterHit!]!
                    totalElements: Int!
                }

                type CharacterHit {
                    score: Float!
                    source: Character!
                }

                interface Character {
                    name: String!
                }

                type Human implements Character {
                    name: String!
                    homePlanet: String!
                }

                type Droid implements Character {
                    name: String!
                    primaryFunction: String!
                }
                """)
            .resolvers(CharacterHitQueryResolver())
            .dictionary(Human::class, Droid::class)
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                characterHit {
                    score
                    source { name }
                }
                characterHitPage {
                    totalElements
                    content {
                        source {
                            name
                            ... on Human { homePlanet }
                            ... on Droid { primaryFunction }
                        }
                    }
                }
            }
            """
        }

        assertEquals(data["characterHit"], mapOf("score" to 0.5, "source" to mapOf("name" to "Luke")))
        assertEquals(data["characterHitPage"], mapOf(
            "totalElements" to 2,
            "content" to listOf(
                mapOf("source" to mapOf("name" to "Luke", "homePlanet" to "Tatooine")),
                mapOf("source" to mapOf("name" to "R2-D2", "primaryFunction" to "Astromech"))
            )
        ))
    }

    class EntityMatchQueryResolver : GraphQLQueryResolver {
        fun test(): TestClass = TestClass(
            match = EntityMatch("x y", listOf("x", "y")),
            single = EntityMatch("z", "z")
        )
    }

    data class TestClass(val match: EntityMatch<List<String>>, val single: EntityMatch<String>)

    data class EntityMatch<T>(val text: String, val value: T?)

    class CharacterHitQueryResolver : GraphQLQueryResolver {
        fun characterHit(): Hit<Character> = Hit(Human("Luke", "Tatooine"), 0.5)
        fun characterHitPage(): Page<Hit<Character>> = Page(
            listOf(Hit(Human("Luke", "Tatooine"), 1.0), Hit(Droid("R2-D2", "Astromech"), 1.0)),
            2
        )
    }

    class Page<T>(val content: List<T>, val totalElements: Int)

    class Hit<T>(val source: T, val score: Double)

    interface Character {
        val name: String
    }

    class Human(override val name: String, val homePlanet: String) : Character

    class Droid(override val name: String, val primaryFunction: String) : Character
}
