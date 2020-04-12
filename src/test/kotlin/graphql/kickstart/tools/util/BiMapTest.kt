package graphql.kickstart.tools.util

import org.hamcrest.CoreMatchers
import org.junit.Assert
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
        Assert.assertEquals(2, old.toLong())

        // inverse should have correct keys
        val expected: Set<Int> = HashSet(Arrays.asList(1, 3))
        Assert.assertEquals(expected, bimap.values)
        Assert.assertEquals(expected, bimap.inverse().keys)
    }

    @Test
    fun `should provide inverse view`() {
        bimap["foo"] = 1
        bimap["bar"] = 2
        bimap["baz"] = 3
        val inverse = bimap.inverse()
        Assert.assertTrue(inverse.containsKey(1))
        Assert.assertTrue(inverse.containsKey(2))
        Assert.assertTrue(inverse.containsKey(3))
        Assert.assertThat(inverse[1], CoreMatchers.`is`("foo"))
        Assert.assertThat(inverse[2], CoreMatchers.`is`("bar"))
        Assert.assertThat(inverse[3], CoreMatchers.`is`("baz"))
    }

    @Test
    fun `should return correct values`() {
        bimap["foo"] = 1
        bimap["bar"] = 2
        bimap["baz"] = 3
        val values = bimap.values
        val expected = setOf(1, 2, 3)
        Assert.assertEquals(expected, values)
    }
}
