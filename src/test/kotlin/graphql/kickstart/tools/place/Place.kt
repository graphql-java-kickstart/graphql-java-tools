package graphql.kickstart.tools.place

abstract class Place<R : Review<*>>(id: String? = null) : Entity(id) {

    val name: String? = null
    val reviews: MutableSet<R>? = null

}