package graphql.kickstart.tools

import graphql.schema.DataFetchingEnvironment

interface MissingFieldHandler {
    fun resolve(env: DataFetchingEnvironment?): Any?
}