package graphql.kickstart.tools.proxy;

import graphql.kickstart.tools.GraphQLResolver;

public class WeldProxyHandler implements ProxyHandler {

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
