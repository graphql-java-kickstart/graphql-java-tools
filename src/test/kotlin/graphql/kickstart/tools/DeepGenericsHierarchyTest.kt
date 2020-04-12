package graphql.kickstart.tools

import graphql.ExecutionInput
import graphql.GraphQL
import org.junit.Test

class PlaceTest {

    @Test
    fun `should handle generics deep hierarchy`() {
        val schema = SchemaParser.newParser()
            .file("Place.graphqls")
            .resolvers(PlaceQuery())
            .build().makeExecutableSchema()

        val graphql = GraphQL.newGraphQL(schema).build()
        val query = "query { places1 { id } places2 { id } }"
        val executionInput = ExecutionInput.newExecutionInput().query(query).build()
        val result = graphql.execute(executionInput)

        assert(result.getData<Map<String, List<*>>>()["places1"]?.size == 3)
        assert(result.getData<Map<String, List<*>>>()["places2"]?.size == 2)
    }
}

private class PlaceQuery : GraphQLQueryResolver {

    fun places1(): List<Place1> = listOf(Place1("1"), Place1("2"), Place1("3"))

    fun places2(): List<Place2> = listOf(Place2("4"), Place2("5"))
}

private abstract class Entity(val id: String? = null)

private abstract class OtherPlace<R : Review<*>>(id: String? = null) : Place<R>(id) {
    val other: String? = null
}

private abstract class Place<R : Review<*>>(id: String? = null) : Entity(id) {
    val name: String? = null
    val reviews: MutableSet<R>? = null
}

private class Place1(id: String? = null) : OtherPlace<Review1>(id)

private class Place2(id: String? = null) : OtherPlace<Review2>(id)

private abstract class Review<T : Entity>(id: String? = null) : Entity(id) {
    val rating: Int? = null
    val content: T? = null
}

private class Review1(id: String? = null) : Review<Place1>(id)

private class Review2(id: String? = null) : Review<Place2>(id)
