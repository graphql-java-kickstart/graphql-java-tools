package graphql.kickstart.tools.compat.scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.support.AopUtils

object JdkProxy : Scenario {
    override val name = "jdk-proxy"

    override val files = listOf("compat/scenarios/jdk-proxy/schema.graphqls")
    override val resolvers get() = listOf(proxy(proxyTargetClass = false).also { check(AopUtils.isJdkDynamicProxy(it)) })
}

object JdkProxyCglib : Scenario {
    override val name = "jdk-proxy-cglib"
    override val operations = "jdk-proxy"

    override val files = JdkProxy.files
    override val resolvers get() = listOf(proxy(proxyTargetClass = true).also { check(AopUtils.isCglibProxy(it)) })
}

object JdkProxyPlain : Scenario {
    override val name = "jdk-proxy-plain"
    override val operations = "jdk-proxy"

    override val files = JdkProxy.files
    override val resolvers = listOf(ProxiedQuery())
}

open class ProxiedQuery : GraphQLQueryResolver {
    open fun test() = "ok"

    @JvmField
    val count = 1

    open val total = 2
}

private fun proxy(proxyTargetClass: Boolean) =
    ProxyFactory(ProxiedQuery()).apply { isProxyTargetClass = proxyTargetClass }.proxy as GraphQLQueryResolver
