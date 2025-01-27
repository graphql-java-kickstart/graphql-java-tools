package graphql.kickstart.tools.util

class UtilsTest {

    @Suppress("unused")
    private class Bean {
        fun getterValid(): String = ""

        fun getterWithArgument(@Suppress("UNUSED_PARAMETER") arg1: String): String = ""

        internal fun getterInternal(): String = ""

        fun notAGetter(): String = ""

        fun isString(): String = ""

        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        fun isJavaBoolean(): java.lang.Boolean = java.lang.Boolean(false)

        fun isKotlinBoolean(): Boolean = false
    }

    private class UtilsTestTrivialDataFetcherBean {
        val isBooleanPrimitive = false
    }
}
