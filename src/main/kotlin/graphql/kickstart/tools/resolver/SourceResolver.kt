package graphql.kickstart.tools.resolver

import graphql.schema.DataFetchingEnvironment

fun interface SourceResolver {

    fun resolve(environment: DataFetchingEnvironment?, source: Any?): Any
}
