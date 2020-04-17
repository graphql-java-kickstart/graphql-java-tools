package graphql.kickstart.tools

import kotlin.coroutines.CoroutineContext

interface CoroutineContextProvider {
    fun provide(): CoroutineContext?
}
