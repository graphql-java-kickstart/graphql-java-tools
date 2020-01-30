package graphql.kickstart.tools

import org.junit.Assert
import org.junit.Test

class UtilsTest {

    @Suppress("unused")
    class Bean {
        fun getterValid(): String = ""

        fun getterWithArgument(@Suppress("UNUSED_PARAMETER") arg1: String): String = ""

        internal fun getterInternal(): String = ""

        fun notAGetter(): String = ""

        fun isString(): String = ""

        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        fun isJavaBoolean(): java.lang.Boolean = java.lang.Boolean(false)

        fun isKotlinBoolean(): Boolean = false
    }

    @Test
    fun `isTrivialDataFetcher`() {
        val clazz = Bean::class.java

        Assert.assertTrue(isTrivialDataFetcher(clazz.getMethod("getterValid")))
        Assert.assertFalse(isTrivialDataFetcher(clazz.getMethod("getterWithArgument", String::class.java)))
        Assert.assertFalse(isTrivialDataFetcher(clazz.getMethod("notAGetter")))

        Assert.assertFalse(isTrivialDataFetcher(clazz.getMethod("isString")))
        Assert.assertTrue(isTrivialDataFetcher(clazz.getMethod("isJavaBoolean")))
        Assert.assertTrue(isTrivialDataFetcher(clazz.getMethod("isKotlinBoolean")))

        Assert.assertTrue(isTrivialDataFetcher(UtilsTestTrivialDataFetcherBean::class.java.getMethod("isBooleanPrimitive")))
    }
}
