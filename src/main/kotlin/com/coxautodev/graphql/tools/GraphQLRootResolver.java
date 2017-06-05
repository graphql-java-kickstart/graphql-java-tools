package com.coxautodev.graphql.tools;

/**
 * @author Andrew Potter
 */
public interface GraphQLRootResolver extends GraphQLResolver<Void> {
    default String getResolverName() {
        return null;
    }
}
