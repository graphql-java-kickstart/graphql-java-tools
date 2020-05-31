package graphql.kickstart.tools

import java.lang.reflect.Method

data class MethodFieldResolverContext(
    val method: Method,
    val arguments: List<Any?>
)

interface MethodFieldResolverInterceptor {
    fun beforeInvoke(context: MethodFieldResolverContext) {}
    fun afterInvoke(context: MethodFieldResolverContext, result: Any?) {}
}
