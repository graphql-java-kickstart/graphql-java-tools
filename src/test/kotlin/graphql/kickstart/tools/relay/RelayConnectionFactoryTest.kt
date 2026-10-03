package graphql.kickstart.tools.relay

import graphql.kickstart.tools.SchemaError
import graphql.language.Definition
import graphql.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayConnectionFactoryTest {

    @Test
    fun `should not add new definition when no @connection directive`() {
        // setup
        val factory = RelayConnectionFactory()
        val existing = mutableListOf<Definition<*>>()

        val newDefinitions = factory.create(existing)

        // expect
        assertEquals(newDefinitions.size, 0)
    }

    @Test
    fun `should throw schema error when @connection is missing the for argument`() {
        val factory = RelayConnectionFactory()
        val existing = Parser.parse(
            """
            type Query {
                users: UserConnection @connection
            }
            """
        ).definitions.toMutableList()

        val error = assertThrows(SchemaError::class.java) { factory.create(existing) }

        assertEquals("@connection directive is missing the required 'for' argument", error.message)
    }
}
