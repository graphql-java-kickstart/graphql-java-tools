package unwrapcheck;

import graphql.kickstart.tools.GraphQLQueryResolver;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class Fixtures {

    public static volatile Object nextReturnValue;

    private Fixtures() {
    }

    public static final class HolderQuery implements GraphQLQueryResolver {
        public Object holder() {
            return nextReturnValue;
        }
    }

    public static final class Payload {
        private final String data;

        public Payload(String data) {
            this.data = data;
        }

        public String getData() {
            return data;
        }
    }

    public static final class FutureItem implements Future<String> {
        private final String data;

        public FutureItem(String data) {
            this.data = data;
        }

        public String getData() {
            return data;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public String get() {
            return data;
        }

        @Override
        public String get(long timeout, TimeUnit unit) {
            return data;
        }
    }

    public static final class Try<T> {
        private final T value;
        private final String failure;

        private Try(T value, String failure) {
            this.value = value;
            this.failure = failure;
        }

        public static <T> Try<T> success(T value) {
            return new Try<>(value, null);
        }

        public static <T> Try<T> failure(String message) {
            return new Try<>(null, Objects.requireNonNull(message));
        }

        public T get() {
            if (failure != null) {
                throw new IllegalStateException(failure);
            }
            return value;
        }
    }

    public static final class Page<T> {
        private final List<T> content;

        public Page(List<T> content) {
            this.content = content;
        }

        public List<T> getContent() {
            return content;
        }
    }

    public static final class Later<T> {
        private final CompletableFuture<T> future;

        public Later(CompletableFuture<T> future) {
            this.future = future;
        }

        public CompletableFuture<T> toFuture() {
            return future;
        }
    }
}
