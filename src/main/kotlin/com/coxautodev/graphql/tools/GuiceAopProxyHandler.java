package com.coxautodev.graphql.tools;

public class GuiceAopProxyHandler implements ProxyHandler {
    @Override
    public boolean canHandle(GraphQLResolver<?> resolver) {
        return isGuiceProxy(resolver);
    }

    @Override
    public Class<?> getTargetClass(GraphQLResolver<?> resolver) {
        Class<?> targetClass = resolver.getClass();
        if (isGuiceProxy(resolver)) {
            return resolver.getClass().getSuperclass();
        } else {
            return targetClass;
        }
    }

    private boolean isGuiceProxy(GraphQLResolver<?> resolver) {
        return resolver.getClass().getName().contains("$$EnhancerByGuice$$");
    }
}
