package graphql.kickstart.tools

import graphql.GraphQL
import org.junit.Assert.assertThrows
import org.junit.Test

class PropertyMapListTest {

    @Test
    fun `property maps should support list fields`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    greeting: Greeting!
                }

                type Greeting {
                    name: String!
                    tags: [String!]!
                    matrix: [[Int]]
                    key: Key
                    keys: [Key!]
                }

                type Key {
                    a: String
                    b: [String]
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun greeting(): Map<String, Any> = mapOf(
                    "name" to "hello",
                    "tags" to listOf("x", "y"),
                    "matrix" to listOf(listOf(1, 2), listOf(3)),
                    "key" to hashMapOf("a" to "one", "b" to listOf("ss")),
                    "keys" to listOf(hashMapOf("a" to "two", "b" to listOf("tt")))
                )
            })
            .dictionary("Key", HashMap::class)
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            {
                greeting {
                    name
                    tags
                    matrix
                    key { a b }
                    keys { a b }
                }
            }
            """
        }

        assertEquals(data["greeting"], mapOf(
            "name" to "hello",
            "tags" to listOf("x", "y"),
            "matrix" to listOf(listOf(1, 2), listOf(3)),
            "key" to mapOf("a" to "one", "b" to listOf("ss")),
            "keys" to listOf(mapOf("a" to "two", "b" to listOf("tt")))
        ))
    }

    @Test
    fun `property maps with typed list values should support list fields`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    tags: Tags!
                }

                type Tags {
                    names: [String!]!
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun tags(): TagsMap = TagsMap().apply { put("names", listOf("x", "y")) }
            })
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            {
                tags {
                    names
                }
            }
            """
        }

        assertEquals(data["tags"], mapOf("names" to listOf("x", "y")))
    }

    @Test
    fun `property maps with object list fields should require a dictionary entry`() {
        val error = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        greeting: Greeting!
                    }

                    type Greeting {
                        keys: [Key!]
                    }

                    type Key {
                        a: String
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun greeting(): Map<String, Any> = mapOf()
                })
                .build()
        }

        assertEquals(error.message, "The GraphQL schema type 'Key' maps to a field of type java.lang.Object however there is no matching entry for this type in the type dictionary. You may need to add this type to the dictionary before building the schema.")
    }

    class TagsMap : HashMap<String, List<String>>()
}
