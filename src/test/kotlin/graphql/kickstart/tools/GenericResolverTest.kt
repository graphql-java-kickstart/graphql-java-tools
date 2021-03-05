package graphql.kickstart.tools

import org.junit.Test

class GenericResolverTest {

    @Test
    fun `methods from generic resolvers are resolved`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    bar: Bar
                }
        
                type Bar {
                    value: String
                }
                """)
            .resolvers(QueryResolver1(), BarResolver())
            .build()
            .makeExecutableSchema()
    }

    class QueryResolver1 : GraphQLQueryResolver {
        fun getBar(): Bar {
            return Bar()
        }
    }

    class Bar

    abstract class FooResolver<T> : GraphQLResolver<T> {
        fun getValue(foo: T): String = "value"
    }

    class BarResolver : FooResolver<Bar>(), GraphQLResolver<Bar>

    @Test
    fun `methods from generic inherited resolvers are resolved`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    car: Car
                }
                type Car {
                    value: String
                }
                """)
            .resolvers(QueryResolver2(), CarResolver())
            .build()
            .makeExecutableSchema()
    }

    class QueryResolver2 : GraphQLQueryResolver {
        fun getCar(): Car = Car()
    }

    abstract class FooGraphQLResolver<T> : GraphQLResolver<T> {
        fun getValue(foo: T): String = "value"
    }

    class Car

    class CarResolver : FooGraphQLResolver<Car>()
}
