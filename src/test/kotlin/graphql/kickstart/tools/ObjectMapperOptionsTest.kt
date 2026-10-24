package graphql.kickstart.tools

import graphql.GraphQL
import graphql.language.FieldDefinition
import graphql.language.TypeName
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import tools.jackson.module.kotlin.KotlinFeature
import tools.jackson.module.kotlin.KotlinModule

class ObjectMapperOptionsTest {

    @Test
    fun `enums are converted to and from Strings by name`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    names(input: ColorInput!): String
                    colors(input: ColorNameInput!): String
                }

                enum Color {
                    RED
                }

                input ColorInput {
                    color: Color!
                }

                input ColorNameInput {
                    color: String!
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun names(input: Map<String, String>): String = input.getValue("color")
                fun colors(input: ColorNameInput): String = input.color.name
            })
            .dictionary(Color::class)
            .build()
            .makeExecutableSchema()
            .assertEveryFieldResolves(except = setOf("Query.colors"))

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                names(input: { color: RED })
                colors(input: { color: "RED" })
            }
            """
        }

        assertEquals(data, mapOf("names" to "RED", "colors" to "RED"))
    }

    @Test
    fun `fields share the library's ObjectMapper unless a configurer is set`() {
        val first = FieldDefinition.newFieldDefinition().name("first").type(TypeName("String")).build()
        val second = FieldDefinition.newFieldDefinition().name("second").type(TypeName("String")).build()
        val library = SchemaParserOptions.defaultOptions().objectMapperProvider
        val configured = SchemaParserOptions.newOptions().objectMapperConfigurer { _, _ -> }.build().objectMapperProvider

        assertSame(library.provide(first), library.provide(second))
        assertNotSame(configured.provide(first), configured.provide(second))
    }

    @Test
    fun `a KotlinModule from the configurer replaces the library's`() {
        val query = """query { names(input: { names: ["a", null] }) }"""

        val strict = namesGraphQL(SchemaParserOptions.defaultOptions()).execute(query)
        val lenient = namesGraphQL(SchemaParserOptions.newOptions()
            .objectMapperConfigurer { builder, _ -> builder.addModule(KotlinModule.Builder().disable(KotlinFeature.StrictNullChecks).build()) }
            .build()).execute(query)

        assertEquals(strict.errors.single().path, listOf("names"))
        assertEquals(lenient.getData<Map<String, Any?>>(), mapOf("names" to "[a, null]"))
    }

    private fun namesGraphQL(options: SchemaParserOptions): GraphQL {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    names(input: NamesInput!): String
                }

                input NamesInput {
                    names: [String]!
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun names(input: NamesInput): String = input.names.toString()
            })
            .options(options)
            .build()
            .makeExecutableSchema()
            .assertEveryFieldResolves()
        return GraphQL.newGraphQL(schema).build()
    }

    enum class Color {
        RED;

        override fun toString() = "red-ish"
    }

    data class ColorNameInput(val color: Color)

    data class NamesInput(val names: List<String>)
}
