package graphql.kickstart.tools;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.beans.ConstructorProperties;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Input classes as they're written in Java, by hand or with Lombok, so their properties aren't named by the Kotlin module.
public class UnmappedInputFieldsFixtures {

    // the class from https://github.com/graphql-java-kickstart/graphql-java-tools/issues/77#issuecomment-662855626
    public static class ProjectInput {
        private Integer id;
        private String name;
        private String description;
        private List<TechnologyInput> technolgies;
        private List<String> profiles;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<TechnologyInput> getTechnolgies() {
            return technolgies;
        }

        public void setTechnolgies(List<TechnologyInput> technolgies) {
            this.technolgies = technolgies;
        }

        public List<String> getProfiles() {
            return profiles;
        }

        public void setProfiles(List<String> profiles) {
            this.profiles = profiles;
        }
    }

    public static class TechnologyInput {
        private Integer id;
        private String name;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class SetterOnlyInput {
        private String name;
        private NestedInput nested;

        public void setName(String name) {
            this.name = name;
        }

        public void setNested(NestedInput nested) {
            this.nested = nested;
        }

        public String describe() {
            return name + " " + nested.describe();
        }
    }

    public static class NestedInput {
        private String value;

        public void setValue(String value) {
            this.value = value;
        }

        public String describe() {
            return value;
        }
    }

    public static class ConstructorPropertiesInput {
        private final String name;

        @ConstructorProperties({"name"})
        public ConstructorPropertiesInput(String name) {
            this.name = name;
        }

        public String describe() {
            return name;
        }
    }

    public static class PrivateFieldInput {
        @JsonProperty("name")
        private String value;

        public String describe() {
            return value;
        }
    }

    public static class AliasInput {
        private String title;

        @JsonAlias("name")
        public void setTitle(String title) {
            this.title = title;
        }

        public String describe() {
            return title;
        }
    }

    public static class AnySetterInput {
        private final Map<String, Object> values = new TreeMap<>();

        @JsonAnySetter
        public void set(String name, Object value) {
            values.put(name, value);
        }

        public String describe() {
            return values.toString();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IgnoreUnknownInput {
        public String name;
    }

    @JsonIgnoreProperties({"legacy"})
    public static class IgnoredNameInput {
        public String name;
    }

    public static class JsonIgnoreInput {
        public String name;

        @JsonIgnore
        public String secret;
    }

    public static class UnwrappedInput {
        public String name;

        @JsonUnwrapped
        public Address address;
    }

    public static class Address {
        public String street;
    }

    public static class ConflictingCreatorsInput {
        @JsonCreator
        public ConflictingCreatorsInput(@JsonProperty("name") String name) {
        }

        @JsonCreator
        public ConflictingCreatorsInput(@JsonProperty("name") Integer name) {
        }
    }
}
