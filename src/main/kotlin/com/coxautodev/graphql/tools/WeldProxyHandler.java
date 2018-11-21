package com.coxautodev.graphql.tools;

import com.coxautodev.graphql.tools.GraphQLResolver;
import com.coxautodev.graphql.tools.ProxyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WeldProxyHandler implements ProxyHandler
{
    @Override
    public boolean canHandle(GraphQLResolver<?> resolver) {
        return isWeldProxy(resolver);
    }

    @Override
    public Class<?> getTargetClass(GraphQLResolver<?> resolver) {
        return resolver.getClass().getSuperclass();
    }

    private boolean isWeldProxy(GraphQLResolver<?> resolver) {
        return resolver.getClass().getName().contains("$Proxy$_$$_WeldClientProxy");
    }
}
