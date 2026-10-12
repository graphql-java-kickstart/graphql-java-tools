package graphql.kickstart.tools

import com.fasterxml.jackson.databind.node.ObjectNode
import graphql.GraphQL
import org.junit.Assert.assertThrows
import org.junit.Test

class SchemaParserDictionaryTest {

    private val entitySchema = """
        type Query {
            test: String
        }

        type Mutation {
            create(input: EntityCreateInput!): String
            update(input: EntityUpdateInput!): String
        }

        input EntityCreateInput {
            name: String
            nested: Nested
        }

        input EntityUpdateInput {
            id: ID!
            nested: Nested
        }

        input Nested {
            x: Int
        }
        """

    private val directiveSchema = """
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
        """

    @Test
    fun `input types can share a dictionary class`() {
        val schema = SchemaParser.newParser()
            .schemaString(entitySchema)
            .resolvers(EntityQuery(), EntityMutation())
            .dictionary("EntityCreateInput", ObjectNode::class.java)
            .dictionary("EntityUpdateInput", ObjectNode::class.java)
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            mutation {
                create(input: { name: "n", nested: { x: 1 } })
                update(input: { id: "2", nested: { x: 3 } })
            }
            """
        }

        assertEquals(data["create"], """{"name":"n","nested":{"x":1}}""")
        assertEquals(data["update"], """{"id":"2","nested":{"x":3}}""")
    }

    @Test
    fun `nested input types can share a dictionary class`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    create(input: EntityInput!): String
                }

                input EntityInput {
                    name: String
                    a: NestedA
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
            })
            .dictionary("NestedA", ObjectNode::class.java)
            .dictionary("NestedB", ObjectNode::class.java)
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                create(input: { name: "n", a: { x: 1 }, b: [{ y: 2 }] })
            }
            """
        }

        assertEquals(data["create"], """{"name":"n","a":{"x":1},"b":[{"y":2}]}""")
    }

    @Test
    fun `directive input types can share a dictionary class`() {
        val schema = SchemaParser.newParser()
            .schemaString(directiveSchema)
            .resolvers(DirectiveQuery())
            .dictionary("AInput", Map::class.java)
            .dictionary("BInput", Map::class.java)
            .build()
            .makeExecutableSchema()

        val field = schema.queryType.getFieldDefinition("test")
        assertEquals(field.getAppliedDirective("a").getArgument("arg")!!.getValue<Map<*, *>>(), mapOf("x" to 1))
        assertEquals(field.getAppliedDirective("b").getArgument("arg")!!.getValue<Map<*, *>>(), mapOf("y" to "b"))

        val gql = GraphQL.newGraphQL(schema).build()
        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                test
            }
            """
        }

        assertEquals(data["test"], "test")
    }

    @Test
    fun `input types sharing a class can be passed to the builder from a SchemaParserDictionary`() {
        val dictionary = SchemaParserDictionary()
            .add("AInput", Map::class)
            .add("BInput", Map::class)

        assertEquals(dictionary.getDictionary().toMap(), mapOf("AInput" to Map::class.java, "BInput" to Map::class.java))

        // graphql-spring-boot reads a SchemaParserDictionary bean this way
        val schema = SchemaParser.newParser()
            .schemaString(directiveSchema)
            .resolvers(DirectiveQuery())
            .dictionary(dictionary.getDictionary())
            .build()
            .makeExecutableSchema()

        val field = schema.queryType.getFieldDefinition("test")
        assertEquals(field.getAppliedDirective("b").getArgument("arg")!!.getValue<Map<*, *>>(), mapOf("y" to "b"))
    }

    @Test
    fun `input type and object type can share a dictionary class`() {
        // ItemInput is only used by a directive, so its class can only come from the dictionary
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @d(arg: ItemInput) on FIELD_DEFINITION

                type Query {
                    node: Node @d(arg: { name: "z" })
                }

                interface Node {
                    name: String
                }

                type Item implements Node {
                    name: String
                }

                input ItemInput {
                    name: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun node(): Node = Item().apply { name = "a" }
            })
            .dictionary("Item", Item::class.java)
            .dictionary("ItemInput", Item::class.java)
            .build()
            .makeExecutableSchema()

        val field = schema.queryType.getFieldDefinition("node")
        assertEquals(field.getAppliedDirective("d").getArgument("arg")!!.getValue<Map<*, *>>(), mapOf("name" to "z"))

        val gql = GraphQL.newGraphQL(schema).build()
        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                node {
                    __typename
                    name
                }
            }
            """
        }

        assertEquals(data["node"], mapOf("__typename" to "Item", "name" to "a"))
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
                        name: String
                    }

                    type B {
                        name: String
                    }

                    input AInput {
                        name: String
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun a(): Item? = null
                    fun b(): Item? = null
                })
                .dictionary("A", Item::class.java)
                .dictionary("AInput", Item::class.java)
                .dictionary("B", Item::class.java)
                .build()
        }

        // the input type sharing the class isn't part of the conflict
        assertEquals(error.message, "Class in supplied dictionary '${Item::class.java.name}' specified type names 'A', 'B', " +
            "but only input object types can share a class!")
    }

    class EntityQuery : GraphQLQueryResolver {
        fun test(): String? = null
    }

    class EntityMutation : GraphQLMutationResolver {
        fun create(input: ObjectNode): String = input.toString()
        fun update(input: ObjectNode): String = input.toString()
    }

    class DirectiveQuery : GraphQLQueryResolver {
        fun test(): String = "test"
    }

    interface Node {
        val name: String?
    }

    class Item : Node {
        override var name: String? = null
    }
}
