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

public class SchemaClassScannerSealedJavaTypesTest {

    @Test
    public void scannerFindsUnionMembersAndInterfaceImplementorsAmongThePermittedSubclassesOfSealedTypes() {
        GraphQLSchema schema = SchemaParser.newParser()
            .schemaString("""
                type Query {
                    pets: [Pet]
                    animals: [Animal]
                }

                union Pet = Dog | Fish

                type Dog {
                    name: String
                }

                type Fish {
                    fins: Int
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
            .resolvers(new PetQuery(), new AnimalQuery())
            .build()
            .makeExecutableSchema();
        EveryFieldResolvesKt.assertEveryFieldResolves(schema);

        ExecutionResult result = GraphQL.newGraphQL(schema).build().execute(ExecutionInput.newExecutionInput().query("""
            query {
                pets { __typename ... on Dog { name } ... on Fish { fins } }
                animals { __typename name ... on Cat { lives } ... on Bird { wings } }
            }
            """));

        assertTrue(result.getErrors().toString(), result.getErrors().isEmpty());
        assertEquals(Map.of(
            "pets", List.of(Map.of("__typename", "Dog", "name", "Rex"), Map.of("__typename", "Fish", "fins", 2)),
            "animals", List.of(Map.of("__typename", "Cat", "name", "Tom", "lives", 9), Map.of("__typename", "Bird", "name", "Tweety", "wings", 2))
        ), result.getData());
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

    public static class AnimalQuery implements GraphQLQueryResolver {
        public List<Animal> animals() {
            return List.of(new Cat("Tom", 9), new Bird("Tweety", 2));
        }
    }

    public abstract static sealed class Animal permits Cat, Bird {
        final String name;

        Animal(String name) {
            this.name = name;
        }
    }

    public static final class Cat extends Animal {
        final Integer lives;

        Cat(String name, Integer lives) {
            super(name);
            this.lives = lives;
        }
    }

    public static final class Bird extends Animal {
        final Integer wings;

        Bird(String name, Integer wings) {
            super(name);
            this.wings = wings;
        }
    }
}
