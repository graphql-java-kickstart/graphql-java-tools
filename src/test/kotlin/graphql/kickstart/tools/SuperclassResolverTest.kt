package graphql.kickstart.tools

import org.junit.Test

class SuperclassResolverTest {

    @Test
    fun `methods from generic resolvers are resolved`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    bar: Bar!
                }
    
                type Bar implements Foo{
                    value: String
                    getValueWithSeveralParameters(arg1: Boolean!, arg2: String): String!
                }
        
                interface Foo {
                    getValueWithSeveralParameters(arg1: Boolean!, arg2: String): String!
                }
                """)
            .resolvers(QueryResolver(), BarResolver())
            .build()
            .makeExecutableSchema()
    }

    class QueryResolver : GraphQLQueryResolver {
        fun getBar(): Bar = Bar()
    }

    class Bar

    abstract class FooResolver<T> : GraphQLResolver<Bar> {
        fun getValue(foo: T): String = "value"

        fun getValueWithSeveralParameters(foo: T, arg1: Boolean, arg2: String): String {
            return if (arg1) {
                "value"
            } else {
                arg2
            }
        }
    }

    class BarResolver : FooResolver<Bar>()
}
