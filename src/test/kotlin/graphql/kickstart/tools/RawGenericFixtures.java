package graphql.kickstart.tools;

import java.util.List;
import java.util.concurrent.CompletableFuture;

// Raw types can't be expressed in Kotlin, so these fixtures must stay in Java.
public class RawGenericFixtures {

    public static class QueryResolver implements GraphQLQueryResolver {

        @SuppressWarnings({"rawtypes", "unused"})
        public Tree tree() {
            return new Tree<>("leaf");
        }
    }

    public static class Tree<T> {
        private final T value;

        public Tree(T value) {
            this.value = value;
        }

        public T getValue() {
            return value;
        }

        // returned from a raw Tree, these are trees whose T is bound to a type containing that same, unbound T
        public Tree<List<T>> getGrouped() {
            return new Tree<>(List.of(value));
        }

        public Tree<CompletableFuture<T>> getAsync() {
            return new Tree<>(CompletableFuture.completedFuture(value));
        }
    }
}
