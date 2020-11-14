package graphql.kickstart.tools

import graphql.schema.DataFetchingEnvironment

interface MissingFieldResolverHandler {
    fun resolve(env: DataFetchingEnvironment?): Any?
}
