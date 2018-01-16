package com.coxautodev.graphql.tools;

public class GuiceAopProxyHandler implements ProxyHandler {
    @Override
    public boolean canHandle(GraphQLResolver<?> resolver) {
        return isGuiceProxy(resolver);
    }

    @Override
    public Class<?> getTargetClass(GraphQLResolver<?> resolver) {
        return resolver.getClass().getSuperclass();
    }

    private boolean isGuiceProxy(GraphQLResolver<?> resolver) {
        return resolver.getClass().getName().contains("$$EnhancerByGuice$$");
    }
}
