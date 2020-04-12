package graphql.kickstart.tools.proxy

import graphql.kickstart.tools.GraphQLResolver

/**
 * @author Marcus Thiesen
 */
interface ProxyHandler {

    fun canHandle(resolver: GraphQLResolver<*>): Boolean

    fun getTargetClass(resolver: GraphQLResolver<*>): Class<*>
}
