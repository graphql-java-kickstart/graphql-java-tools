package graphql.kickstart.tools.place

abstract class OtherPlace<R : Review<*>>(id: String? = null) : Place<R>(id) {

    val other: String? = null

}