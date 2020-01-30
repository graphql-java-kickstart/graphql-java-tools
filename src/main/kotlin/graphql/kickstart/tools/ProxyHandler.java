package graphql.kickstart.tools;

/**
 * @author Andrew Potter
 */
public interface ProxyHandler {
    boolean canHandle(GraphQLResolver<?> resolver);
    Class<?> getTargetClass(GraphQLResolver<?> resolver);
}
