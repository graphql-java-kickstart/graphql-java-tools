package graphql.kickstart.tools

import com.fasterxml.jackson.databind.node.ObjectNode
import graphql.GraphQL
import org.junit.Assert.assertThrows
import org.junit.Test

class SchemaParserDictionaryTest {

    @Test
    fun `input types can share a dictionary class`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    create(input: EntityCreateInput!): String
                    update(input: EntityUpdateInput!): String
                }

                input EntityCreateInput {
                    name: String
                    a: NestedA
                }

                input EntityUpdateInput {
                    id: ID!
                    b: [NestedB!]
                }

                input NestedA {
                    x: Int
                }

                input NestedB {
                    y: Int
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun create(input: ObjectNode): String = input.toString()
                fun update(input: ObjectNode): String = input.toString()
            })
            .dictionary("EntityCreateInput", ObjectNode::class.java)
            .dictionary("EntityUpdateInput", ObjectNode::class.java)
            .dictionary("NestedA", ObjectNode::class.java)
            .dictionary("NestedB", ObjectNode::class.java)
            .build()
            .makeExecutableSchema()
        assertEveryFieldResolves(schema)
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                create(input: { name: "n", a: { x: 1 } })
                update(input: { id: "2", b: [{ y: 3 }] })
            }
            """
        }

        assertEquals(data["create"], """{"name":"n","a":{"x":1}}""")
        assertEquals(data["update"], """{"id":"2","b":[{"y":3}]}""")
    }

    @Test
    fun `directive input types sharing a class can be passed to the builder from a SchemaParserDictionary`() {
        val dictionary = SchemaParserDictionary()
            .add("AInput", Map::class)
            .add("BInput", Map::class)

        assertEquals(dictionary.getDictionary().toMap(), mapOf("AInput" to Map::class.java, "BInput" to Map::class.java))

        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @a(arg: AInput) on FIELD_DEFINITION
                directive @b(arg: BInput) on FIELD_DEFINITION

                type Query {
                    test: String @a(arg: { x: 1 }) @b(arg: { y: "b" })
                }

                input AInput {
                    x: Int
                }

                input BInput {
                    y: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun test(): String? = null
            })
            .dictionary(dictionary.getDictionary())
            .build()
            .makeExecutableSchema()
        assertEveryFieldResolves(schema)

        val field = schema.queryType.getFieldDefinition("test")
        assertEquals(field.getAppliedDirective("a").getArgument("arg")?.getValue<Map<*, *>>(), mapOf("x" to 1))
        assertEquals(field.getAppliedDirective("b").getArgument("arg")?.getValue<Map<*, *>>(), mapOf("y" to "b"))
    }

    @Test
    fun `input type and object type can share a dictionary class`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @d(arg: DogInput) on FIELD_DEFINITION

                type Query {
                    dog: Dog @d(arg: { name: "z" })
                }

                interface Dog {
                    name: String
                }

                type LabradorRetriever implements Dog {
                    name: String
                }

                input DogInput {
                    name: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun dog(): Dog = LabradorRetriever(name = "a", color = "black", fluffiness = 1, speed = 1f)
            })
            .dictionary("LabradorRetriever", LabradorRetriever::class)
            .dictionary("DogInput", LabradorRetriever::class)
            .build()
            .makeExecutableSchema()
        assertEveryFieldResolves(schema)

        val field = schema.queryType.getFieldDefinition("dog")
        assertEquals(field.getAppliedDirective("d").getArgument("arg")?.getValue<Map<*, *>>(), mapOf("name" to "z"))

        val gql = GraphQL.newGraphQL(schema).build()
        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                dog {
                    __typename
                    name
                }
            }
            """
        }

        assertEquals(data["dog"], mapOf("__typename" to "LabradorRetriever", "name" to "a"))
    }

    @Test
    fun `object types can't share a dictionary class`() {
        val error = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        a: A
                        b: B
                    }

                    type A {
                        id: Int
                    }

                    type B {
                        id: Int
                    }

                    input AInput {
                        id: Int
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun a(): ThirdItem? = null
                    fun b(): ThirdItem? = null
                })
                .dictionary("A", ThirdItem::class)
                .dictionary("AInput", ThirdItem::class)
                .dictionary("B", ThirdItem::class)
                .build()
        }

        assertEquals(error.message, "Class in supplied dictionary '${ThirdItem::class.java.name}' specified type names 'A', 'B', " +
            "but only input object types can share a class!")
    }
}
