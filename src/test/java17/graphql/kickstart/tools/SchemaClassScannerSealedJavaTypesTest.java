package graphql.kickstart.tools;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// Sealed classes need Java 17, so the java17-tests profile only compiles and runs this on Java 17 and later.
public class SchemaClassScannerSealedJavaTypesTest {

    @Test
    public void scannerFindsMembersOfUnionsAmongThePermittedSubclassesOfSealedInterfaces() {
        GraphQLSchema schema = SchemaParser.newParser()
            .schemaString("""
                type Query {
                    pets: [Pet]
                }

                union Pet = Dog | Fish

                type Dog {
                    name: String
                }

                type Fish {
                    fins: Int
                }
                """)
            .resolvers(new PetQuery())
            .build()
            .makeExecutableSchema();

        Map<String, Object> data = execute(schema, "query { pets { __typename ... on Dog { name } ... on Fish { fins } } }");

        assertEquals(List.of(Map.of("__typename", "Dog", "name", "Rex"), Map.of("__typename", "Fish", "fins", 2)), data.get("pets"));
    }

    public static class PetQuery implements GraphQLQueryResolver {
        public List<Pet> pets() {
            return List.of(new Dog("Rex"), new Fish(2));
        }
    }

    public sealed interface Pet permits Mammal, Fish {
    }

    public sealed interface Mammal extends Pet permits Dog {
    }

    public record Dog(String name) implements Mammal {
    }

    public record Fish(Integer fins) implements Pet {
    }

    @Test
    public void scannerFindsImplementorsOfInterfacesAmongThePermittedSubclassesOfSealedClasses() {
        GraphQLSchema schema = SchemaParser.newParser()
            .schemaString("""
                type Query {
                    animals: [Animal]
                }

                interface Animal {
                    name: String
                }

                type Cat implements Animal {
                    name: String
                    lives: Int
                }

                type Bird implements Animal {
                    name: String
                    wings: Int
                }
                """)
            .resolvers(new AnimalQuery())
            .build()
            .makeExecutableSchema();

        Map<String, Object> data = execute(schema, "query { animals { __typename name ... on Cat { lives } ... on Bird { wings } } }");

        assertEquals(List.of(Map.of("__typename", "Cat", "name", "Tom", "lives", 9), Map.of("__typename", "Bird", "name", "Tweety", "wings", 2)), data.get("animals"));
    }

    public static class AnimalQuery implements GraphQLQueryResolver {
        public List<Animal> animals() {
            return List.of(new Cat("Tom", 9), new Bird("Tweety", 2));
        }
    }

    public abstract static sealed class Animal permits Cat, Bird {
        private final String name;

        Animal(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public static final class Cat extends Animal {
        private final Integer lives;

        Cat(String name, Integer lives) {
            super(name);
            this.lives = lives;
        }

        public Integer getLives() {
            return lives;
        }
    }

    public static final class Bird extends Animal {
        private final Integer wings;

        Bird(String name, Integer wings) {
            super(name);
            this.wings = wings;
        }

        public Integer getWings() {
            return wings;
        }
    }

    private static Map<String, Object> execute(GraphQLSchema schema, String query) {
        ExecutionResult result = GraphQL.newGraphQL(schema).build().execute(ExecutionInput.newExecutionInput().query(query));

        assertTrue(result.getErrors().toString(), result.getErrors().isEmpty());
        return result.getData();
    }
}
