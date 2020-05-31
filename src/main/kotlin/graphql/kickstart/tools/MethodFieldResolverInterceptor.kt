package graphql.kickstart.tools

import graphql.schema.DataFetchingEnvironment
import java.lang.reflect.Method

data class MethodFieldResolverContext(
    val method: Method,
    val arguments: List<Any?>,
    val environment: DataFetchingEnvironment,
    val resolver: Any?
)

interface MethodFieldResolverInterceptor {
    fun beforeInvoke(context: MethodFieldResolverContext) {}
    fun afterInvoke(context: MethodFieldResolverContext, result: Any?) {}
}
