# Async resolvers and subscriptions

Resolvers can return a `CompletableFuture`, be Kotlin `suspend` functions (root resolvers and `GraphQLResolver<T>` field
resolvers alike), or, for subscription fields, return a reactive-streams `Publisher`. The schema declares the plain
result type (`String!`, `Int!`), and graphql-java-tools unwraps the async wrapper.

## Sub-features

- `async-future` resolves a field from a `CompletableFuture<String>`.
- `async-suspend-root` resolves a root field from a `suspend fun` that suspends (`delay`).
- `async-suspend-nested` resolves a list of objects from a `suspend fun`, and a field on each through a suspend
  `GraphQLResolver<Shipment>` method.
- `async-subscription` streams a `Publisher<Int>` from a `GraphQLSubscriptionResolver`, one event per element, then
  completes.

## Expected results

- **Run.** `run async`. Exit `0`. `summary.txt` has
  `operation 01-query: errors=0` and `operation 02-subscription: errors=0 events=3`.
- **Futures and suspend.** `01-query.json` has `data.future = "from a future"`,
  `data.suspended = "from a suspend function"`, and
  `data.shipments = [{"id": "s1", "status": "s1: delivered"}, {"id": "s2", "status": "s2: delivered"}]`.
- **Subscription.** `02-subscription.json` is
  `{"events": [{"data": {"ticks": 1}}, {"data": {"ticks": 2}}, {"data": {"ticks": 3}}]}`, with no `errors` key and no
  `publisherError` entry.

## Gotchas

- Collection stops at stream completion or after 10 seconds. A publisher that never completes prints `subscription did
  not complete within 10 seconds` and records only the events seen so far, which is a finding, not a pass.
- An error from the publisher shows up as a `{"publisherError": ...}` event, not under `errors`.
- `--graphql-java` changes only the runtime graphql-java. The library jar is still the one compiled against the version
  in `pom.xml`.
- The suspend timing isn't asserted. To prove a dispatcher or `coroutineContextProvider` change, add a field that
  reports `Thread.currentThread().name` or the coroutine context to a new scenario.
