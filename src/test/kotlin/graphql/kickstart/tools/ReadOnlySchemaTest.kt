package graphql.kickstart.tools

import org.junit.Test

class ReadOnlySchemaTest {

    @Test
    fun `should build a read-only schema with interfaces, unions and custom directives`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                directive @tag(name: String!) on FIELD_DEFINITION
                directive @contact(name: String!) on SCHEMA

                extend schema @contact(name: "pets-team")

                type Query {
                    animal: Animal
                    pets: [Pet!]!
                }

                type Mutation {
                    adopt(name: String!): Animal!
                }

                interface Animal {
                    name: String! @tag(name: "public")
                }

                type Dog implements Animal {
                    name: String! @tag(name: "public")
                    barks: Boolean!
                }

                type Cat implements Animal {
                    name: String!
                    lives: Int!
                }

                union Pet = Dog | Cat
                """)
            .resolvers(Query(), Mutation())
            .dictionary(Dog::class.java, Cat::class.java)
            .build()
            .parseSchemaObjects()
            .toReadOnlySchema()
            .assertEveryFieldResolves()

        assertNull(schema.mutationType)
        assertEquals(schema.getDirective("tag")?.name, "tag")
        assertEquals(schema.schemaAppliedDirectives.map { it.name }, listOf("contact"))
    }

    private interface Animal {
        val name: String
    }

    private data class Dog(override val name: String, val barks: Boolean) : Animal

    private data class Cat(override val name: String, val lives: Int) : Animal

    private class Query : GraphQLQueryResolver {
        fun animal(): Animal = Cat("Tom", 9)

        fun pets(): List<Any> = listOf(Dog("Rex", true), Cat("Tom", 9))
    }

    private class Mutation : GraphQLMutationResolver {
        fun adopt(name: String): Animal = Dog(name, false)
    }
}
