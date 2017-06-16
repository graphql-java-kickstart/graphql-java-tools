package com.coxautodev.graphql.tools;

public interface GraphQLSubscriptionResolver extends GraphQLRootResolver {
    @Override
    default String getResolverName() {
        return RootTypeInfo.DEFAULT_SUBSCRIPTION_NAME;
    }
}
