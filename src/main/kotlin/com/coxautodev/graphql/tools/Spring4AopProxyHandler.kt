package com.coxautodev.graphql.tools

import org.springframework.aop.support.AopUtils

/**
 * @author Andrew Potter
 */

class Spring4AopProxyHandler: ProxyHandler {

    val isEnabled: Boolean =
        try {
            Class.forName("org.springframework.aop.support.AopUtils")
            true
        } catch (_: ClassNotFoundException) {
            false
        }

    override fun canHandle(resolver: GraphQLResolver<*>?): Boolean {
        return isEnabled && AopUtils.isAopProxy(resolver)
    }

    override fun getTargetClass(resolver: GraphQLResolver<*>?): Class<*> = AopUtils.getTargetClass(resolver)
}