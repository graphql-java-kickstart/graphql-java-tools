package graphql.kickstart.tools.compat.scenarios

import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.GraphQLResolver
import graphql.kickstart.tools.GraphQLSubscriptionResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.publish
import org.reactivestreams.Publisher
import java.util.concurrent.CompletableFuture

object Async : Scenario {
    override val name = "async"

    override val files = listOf("compat/scenarios/async/schema.graphqls")
    override val resolvers = listOf(AsyncQuery(), ShipmentResolver(), AsyncSubscription())
}

data class Shipment(val id: String)

class AsyncQuery : GraphQLQueryResolver {
    fun future(): CompletableFuture<String> = CompletableFuture.supplyAsync { "from a future" }

    suspend fun suspended(): String {
        delay(10)
        return "from a suspend function"
    }

    suspend fun shipments(): List<Shipment> = listOf(Shipment("s1"), Shipment("s2"))
}

class ShipmentResolver : GraphQLResolver<Shipment> {
    suspend fun status(shipment: Shipment): String {
        delay(5)
        return "${shipment.id}: delivered"
    }
}

class AsyncSubscription : GraphQLSubscriptionResolver {
    fun ticks(count: Int): Publisher<Int> = publish {
        repeat(count) {
            delay(10)
            send(it + 1)
        }
    }
}
