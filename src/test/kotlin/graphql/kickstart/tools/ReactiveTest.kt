package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.kickstart.tools.SchemaParser.Companion.newParser
import graphql.kickstart.tools.SchemaParserOptions.Companion.newOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

//import io.reactivex.Single;
//import io.reactivex.internal.operators.single.SingleJust;
//import static io.reactivex.Maybe.just;
@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveTest {

    @Test
    fun futureSucceeds() {
        val options = newOptions() //                .genericWrappers(
            //                        new SchemaParserOptions.GenericWrapper(Single.class, 0),
            //                        new SchemaParserOptions.GenericWrapper(SingleJust.class, 0)
            //                )
            .build()

        val schema = newParser().file("Reactive.graphqls")
            .resolvers(Query())
            .options(options)
            .build()
            .makeExecutableSchema()

        val gql = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()

        assertNoGraphQlErrors(gql) {
            "query { organization(organizationId: 1) { user { id } } }"
        }
    }

    private class Query : GraphQLQueryResolver {
        //        Single<Optional<Organization>> organization(int organizationid) {
        //            return Single.just(Optional.empty()); //CompletableFuture.completedFuture(null);
        //        }
        fun organization(organizationid: Int): Future<Optional<Organization>> {
            return CompletableFuture.completedFuture(Optional.of(Organization()))
        }
    }

    private class Organization {
        private val user: User? = null
    }

    private class User {
        private val id: Long? = null
        private val name: String? = null
    }
}
