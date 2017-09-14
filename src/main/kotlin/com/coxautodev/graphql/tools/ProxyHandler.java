package com.coxautodev.graphql.tools;

/**
 * @author Andrew Potter
 */
public interface ProxyHandler {
    boolean canHandle(GraphQLResolver<?> resolver);
    Class<?> getTargetClass(GraphQLResolver<?> resolver);
}
