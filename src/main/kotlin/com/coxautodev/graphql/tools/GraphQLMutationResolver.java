package com.coxautodev.graphql.tools;

/**
 * @author Andrew Potter
 */
public interface GraphQLMutationResolver extends GraphQLRootResolver {
    @Override
    default String getResolverName() {
        return RootTypeInfo.getDefaultMutationName();
    }
}
