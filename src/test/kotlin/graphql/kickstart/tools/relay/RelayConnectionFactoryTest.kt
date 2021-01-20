package graphql.kickstart.tools.relay

import graphql.language.Definition
import org.junit.Assert
import org.junit.Test

class RelayConnectionFactoryTest {

    @Test
    fun `should not add new definition when no @connection directive`() {
        // setup
        val factory = RelayConnectionFactory()
        val existing = mutableListOf<Definition<*>>()

        val newDefinitions = factory.create(existing)

        // expect
        Assert.assertEquals(newDefinitions.size, 0)
    }
}
