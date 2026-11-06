package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.junit.Test

class WrappedFetchingObjectTest {

    @Test
    fun `an instrumentation in another package reaches the object behind a method's data fetcher by reflection`() {
        val holder = Holder()
        val query = Query(holder)
        val holderResolver = HolderResolver()
        val wrapped = mutableMapOf<String, String>()
        val instrumentation = object : Instrumentation {
            override fun instrumentDataFetcher(dataFetcher: DataFetcher<*>, parameters: InstrumentationFieldFetchParameters, state: InstrumentationState?): DataFetcher<*> =
                DataFetcher { environment ->
                    val fetchingObject = dataFetcher.javaClass.getMethod("getWrappedFetchingObject", DataFetchingEnvironment::class.java).invoke(dataFetcher, environment)
                    wrapped[environment.field.name] = "${dataFetcher.javaClass.name} -> ${identify(fetchingObject, query, holderResolver, holder)}"
                    dataFetcher.get(environment)
                }
        }
        val schema = SchemaParser.newParser()
            .schemaString("type Query { holder: Holder } type Holder { method: String withArgument(x: String): String resolved: String }")
            .resolvers(query, holderResolver)
            .build()
            .makeExecutableSchema()

        val result = GraphQL.newGraphQL(schema).instrumentation(instrumentation).build().execute("{ holder { method withArgument(x: \"a\") resolved } }")

        assertEquals(result.errors, listOf())
        assertEquals(
            wrapped,
            mapOf(
                "holder" to "graphql.kickstart.tools.resolver.LightMethodFieldResolverDataFetcher -> query resolver",
                "method" to "graphql.kickstart.tools.resolver.LightMethodFieldResolverDataFetcher -> source object",
                "withArgument" to "graphql.kickstart.tools.resolver.MethodFieldResolverDataFetcher -> source object",
                "resolved" to "graphql.kickstart.tools.resolver.MethodFieldResolverDataFetcher -> holder resolver"
            )
        )
    }

    private fun identify(fetchingObject: Any, query: Query, holderResolver: HolderResolver, holder: Holder) = when {
        fetchingObject === query -> "query resolver"
        fetchingObject === holderResolver -> "holder resolver"
        fetchingObject === holder -> "source object"
        else -> "unexpected $fetchingObject"
    }

    class Query(private val holder: Holder) : GraphQLQueryResolver {
        fun holder() = holder
    }

    class Holder {
        fun method() = "method"
        fun withArgument(x: String) = "argument $x"
    }

    class HolderResolver : GraphQLResolver<Holder> {
        fun resolved(holder: Holder) = "resolved ${holder.method()}"
    }
}
