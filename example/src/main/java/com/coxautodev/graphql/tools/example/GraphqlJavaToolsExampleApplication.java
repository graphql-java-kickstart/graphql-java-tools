package com.coxautodev.graphql.tools.example;

import graphql.execution.ExecutionStrategy;
import graphql.execution.ExecutorServiceExecutionStrategy;
import graphql.execution.SubscriptionExecutionStrategy;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.observables.ConnectableObservable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootApplication
public class GraphqlJavaToolsExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraphqlJavaToolsExampleApplication.class, args);
    }

    @Bean
    public ExecutorService executorService() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    public ExecutionStrategy queryExecutionStrategy(ExecutorService executorService) {
        return new ExecutorServiceExecutionStrategy(executorService);
    }

    @Bean
    public ExecutionStrategy subscriptionExecutionStrategy() {
        return new SubscriptionExecutionStrategy();
    }

    @Bean
    public Publisher<Integer> counter() {
        Observable<Integer> counterObservable = Observable.create(emitter -> {
            AtomicInteger counter = new AtomicInteger(0);

            Thread thread = new Thread(() -> {
                while(true) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    emitter.onNext(counter.incrementAndGet());
                }
            });

            thread.setDaemon(true);
            thread.start();
        });

        ConnectableObservable<Integer> connectableObservable = counterObservable.share().publish();
        connectableObservable.connect();

        return connectableObservable.toFlowable(BackpressureStrategy.BUFFER);
    }
}
