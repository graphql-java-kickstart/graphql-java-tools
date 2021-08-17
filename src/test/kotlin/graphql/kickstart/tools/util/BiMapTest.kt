package graphql.kickstart.tools.util

import graphql.kickstart.tools.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.*

class BiMapTest {

    lateinit var bimap: BiMap<String, Int>

    @Before
    fun setup() {
        bimap = BiMap.create()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `should not permit duplicate values`() {
        bimap["foo"] = 1
        bimap["bar"] = 1
    }

    @Test
    fun `should allow same key same value`() {
        bimap["foo"] = 1

        // should not fail
        bimap["foo"] = 1
    }

    @Test
    fun `should allow same key new value`() {
        bimap["foo"] = 1
        bimap["bar"] = 2
        val old = bimap.put("bar", 3)!!

        // old value should have been returned
        assertEquals(2, old.toLong())

        // inverse should have correct keys
        val expected: Set<Int> = HashSet(Arrays.asList(1, 3))
        assertEquals(expected, bimap.values)
        assertEquals(expected, bimap.inverse().keys)
    }

    @Test
    fun `should provide inverse view`() {
        bimap["foo"] = 1
        bimap["bar"] = 2
        bimap["baz"] = 3
        val inverse = bimap.inverse()
        assertTrue(inverse.containsKey(1))
        assertTrue(inverse.containsKey(2))
        assertTrue(inverse.containsKey(3))
        assertEquals(inverse[1], "foo")
        assertEquals(inverse[2], "bar")
        assertEquals(inverse[3], "baz")
    }

    @Test
    fun `should return correct values`() {
        bimap["foo"] = 1
        bimap["bar"] = 2
        bimap["baz"] = 3
        val values = bimap.values
        val expected = setOf(1, 2, 3)
        assertEquals(expected, values)
    }
}
