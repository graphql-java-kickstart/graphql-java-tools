package graphql.kickstart.tools.place

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser
import org.junit.Test

class PlaceQuery : GraphQLQueryResolver {
    fun places1(): List<Place1> = listOf(Place1("1"), Place1("2"), Place1("3"))
    fun places2(): List<Place2> = listOf(Place2("4"), Place2("5"))
}

class PlaceTest {

    @Test
    fun shouldHandleGenericsDeepHierarchy() {
        val schema = SchemaParser.newParser()
                .file("place.graphqls")
                .resolvers(PlaceQuery())
                .build().makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()
        val result = gql.execute(ExecutionInput.newExecutionInput().query("query { places1 { id } places2 { id } }").build())
        assert(result.getData<Map<String, List<*>>>()["places1"]?.size == 3)
        assert(result.getData<Map<String, List<*>>>()["places2"]?.size == 2)
    }

}
