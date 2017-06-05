package com.coxautodev.graphql.tools;

/**
 * @author Andrew Potter
 */
public interface GraphQLQueryResolver extends GraphQLRootResolver {
    @Override
    default String getResolverName() {
        return RootTypeInfo.DEFAULT_QUERY_NAME;
    }
}
