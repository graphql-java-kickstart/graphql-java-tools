package graphql.kickstart.tools;

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
