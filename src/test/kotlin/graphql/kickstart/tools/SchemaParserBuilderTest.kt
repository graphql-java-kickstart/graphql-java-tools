package graphql.kickstart.tools

import graphql.kickstart.tools.SchemaParser.Companion.newParser
import graphql.parser.InvalidSyntaxException
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SchemaParserBuilderTest(private val schema: String, private val error: String) {

    @Test
    fun `parser errors should be returned in full`() {
        try {
            newParser()
                    .schemaString(schema)
                    .build()
        } catch (e: InvalidSyntaxException) {
            Assert.assertTrue(e.toString().contains(error))
        }
    }

    companion object {
        @Parameterized.Parameters
        @JvmStatic
        fun data(): Collection<Array<Any>> {
            return listOf(
                    arrayOf("invalid", "offending token 'invalid' at line 1 column 1"),
                    arrayOf("type Query {\ninvalid!\n}", "offending token '!' at line 2 column 8")
            )
        }
    }
}
