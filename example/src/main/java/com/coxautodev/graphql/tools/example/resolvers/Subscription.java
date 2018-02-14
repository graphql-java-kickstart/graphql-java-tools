package com.coxautodev.graphql.tools.example.resolvers;

import com.coxautodev.graphql.tools.GraphQLSubscriptionResolver;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class Subscription implements GraphQLSubscriptionResolver {

    @Autowired
    Publisher<Integer> counter;

    public Publisher<Integer> counter() {
        return counter;
    }
}
