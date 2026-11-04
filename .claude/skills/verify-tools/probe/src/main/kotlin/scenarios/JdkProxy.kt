package scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser
import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.support.AopUtils

/** Issue #822: the resolver behind a Spring JDK dynamic proxy, which only implements GraphQLQueryResolver. */
object JdkProxy : Scenario {
    override val name = "jdk-proxy"

    override fun parser() = proxyParser(proxy(proxyTargetClass = false).also { check(AopUtils.isJdkDynamicProxy(it)) })
}

/** The workaround: the same resolver behind a CGLIB proxy. */
object JdkProxyCglib : Scenario {
    override val name = "jdk-proxy-cglib"
    override val operations = "jdk-proxy"

    override fun parser() = proxyParser(proxy(proxyTargetClass = true).also { check(AopUtils.isCglibProxy(it)) })
}

/** The same resolver without a proxy. */
object JdkProxyPlain : Scenario {
    override val name = "jdk-proxy-plain"
    override val operations = "jdk-proxy"

    override fun parser() = proxyParser(ProxiedQuery())
}

open class ProxiedQuery : GraphQLQueryResolver {
    open fun test() = "ok"

    @JvmField
    val count = 1

    open val total = 2
}

private fun proxy(proxyTargetClass: Boolean) =
    ProxyFactory(ProxiedQuery()).apply { isProxyTargetClass = proxyTargetClass }.proxy as GraphQLQueryResolver

private fun proxyParser(resolver: GraphQLQueryResolver) = SchemaParser.newParser()
    .file("scenarios/jdk-proxy/schema.graphqls")
    .resolvers(resolver)
