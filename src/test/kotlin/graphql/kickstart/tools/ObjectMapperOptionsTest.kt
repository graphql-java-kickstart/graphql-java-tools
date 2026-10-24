package graphql.kickstart.tools

import graphql.GraphQL
import org.junit.Test
import tools.jackson.module.kotlin.KotlinFeature
import tools.jackson.module.kotlin.KotlinModule

class ObjectMapperOptionsTest {

    @Test
    fun `a KotlinModule from the configurer replaces the library's`() {
        val query = """query { names(input: { names: ["a", null] }) }"""

        val strict = graphQL(SchemaParserOptions.defaultOptions()).execute(query)
        val lenient = graphQL(SchemaParserOptions.newOptions()
            .objectMapperConfigurer { builder, _ -> builder.addModule(KotlinModule.Builder().disable(KotlinFeature.StrictNullChecks).build()) }
            .build()).execute(query)

        assertEquals(strict.errors.single().path, listOf("names"))
        assertEquals(lenient.getData<Map<String, Any?>>(), mapOf("names" to "[a, null]"))
    }

    private fun graphQL(options: SchemaParserOptions): GraphQL {
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

    data class NamesInput(val names: List<String>)
}
