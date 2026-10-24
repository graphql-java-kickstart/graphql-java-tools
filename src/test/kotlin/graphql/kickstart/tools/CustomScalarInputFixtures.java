package graphql.kickstart.tools;

// Java, since Jackson writes a Kotlin class without visible properties as {} even with FAIL_ON_EMPTY_BEANS on
public class CustomScalarInputFixtures {

    public static class Secret {
        private final String value;

        public Secret() {
            this("default");
        }

        public Secret(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
