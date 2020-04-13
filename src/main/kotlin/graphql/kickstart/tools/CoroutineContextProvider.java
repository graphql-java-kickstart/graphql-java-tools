package graphql.kickstart.tools;

import kotlin.coroutines.CoroutineContext;

public interface CoroutineContextProvider {

    CoroutineContext provide();
}
