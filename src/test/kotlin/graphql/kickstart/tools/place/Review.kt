package graphql.kickstart.tools.place

abstract class Review<T : Entity>(id: String? = null) : Entity(id) {

    val rating: Int? = null
    val content: T? = null

}