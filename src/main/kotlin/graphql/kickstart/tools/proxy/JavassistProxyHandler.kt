package graphql.kickstart.tools.proxy

import graphql.kickstart.tools.GraphQLResolver
import javassist.util.proxy.ProxyFactory

/**
 * @author Marcus Thiesen
 */
class JavassistProxyHandler : ProxyHandler {

    private val isEnabled: Boolean =
        try {
            Class.forName("javassist.util.proxy.ProxyFactory")
            true
        } catch (_: ClassNotFoundException) {
            false
        }

    override fun canHandle(resolver: GraphQLResolver<*>): Boolean {
        return isEnabled && ProxyFactory.isProxyClass(resolver.javaClass)
    }

    override fun getTargetClass(resolver: GraphQLResolver<*>): Class<*> = resolver.javaClass.superclass
}
