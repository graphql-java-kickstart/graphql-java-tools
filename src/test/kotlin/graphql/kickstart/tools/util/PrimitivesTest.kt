package graphql.kickstart.tools.util

import org.junit.Assert
import org.junit.Test

class PrimitivesTest {

    @Test
    fun `should wrap primitive Java class`() {
        val clazz = Primitives.wrap(Int::class.javaPrimitiveType)
        Assert.assertEquals(Integer::class.java, clazz)
    }

    @Test
    fun `should not wrap non-primitive class`() {
        val clazz = Primitives.wrap(String::class.java)
        Assert.assertEquals(String::class.java, clazz)
    }

    @Test
    fun `should return null for null`() {
        val clazz = Primitives.wrap<Any>(null)
        Assert.assertNull(clazz)
    }
}
