# Generic wrappers

A resolver can return a value in a wrapper type registered with `genericWrappers(...)`, such as the scenario's `Try<T>`, or
in a `CompletableFuture`, while the schema declares the plain type. The wrappers can sit inside a list or inside each
other, as in `List<Try<Payload>>` or `CompletableFuture<List<Try<Payload>>>`. graphql-java-tools unwraps every level, maps
the lists, and combines the futures below the outermost value into one, so one failed element fails the whole field.

## Sub-features

- `wrap-list` unwraps a `List<Try<Payload>>` into `[Payload!]!` and a `List<Try<String>>` into `[String!]!`.
- `wrap-future-list` unwraps a `List<CompletableFuture<NestedItem>>` into `[Item]` and a
  `List<CompletableFuture<String>>` into `[String]`, including the futures that `DataLoader.load` returns.
- `wrap-nested` unwraps a `CompletableFuture<CompletableFuture<NestedItem>>`, a `CompletableFuture<List<Try<Payload>>>` and
  a `List<Try<CompletableFuture<NestedItem>>>`.
- `wrap-failed-element` fails the whole field, with the element's message, when one `Try` or future in a list failed.
- `wrap-controls` keeps a `List<NestedItem>` and a `CompletableFuture<List<NestedItem>>` working.
- `wrap-unboxer` gives the same results when the app's own `ValueUnboxer` already unwraps `Try` and joins futures
  (`nested-wrappers-unboxer`).
- `wrap-suspend` builds and resolves suspend functions returning `List<CompletableFuture<NestedItem>>` for `[Item]` and
  `[Item!]!`, and `List<T>` for `[String]`. The return type of a suspend function carries the wildcard
  `List<? extends CompletableFuture<NestedItem>>` (`nested-wrappers-suspend`).

## Expected results

- **Run.** `run nested-wrappers`. Exit `0` and an empty `probe.log`. `summary.txt` has `errors=0` for `01` to `09` and `12`,
  and `errors=1` for `10-failed-try-in-list` and `11-failed-future-in-list`.
- **Lists of wrappers.** `02-payloads.json` has `data.payloads = [{"data": "a"}, {"data": "b"}]` and
  `03-payload-strings.json` has `data.payloadStrings = ["a", "b"]`. `04-items.json` has
  `data.items = [{"id": "1"}, {"id": "2"}]`, one future already complete and one from `supplyAsync`, and
  `05-item-names.json` has `data.itemNames = ["one", "two"]`.
- **Nested wrappers.** `06-future-of-future.json` has `data.futureOfFuture = {"id": "inner"}`. `07-future-of-tries.json`
  has `data.futureOfTries = [{"data": "a"}, {"data": "b"}]` and `08-tries-of-futures.json` has
  `data.triesOfFutures = [{"id": "1"}, {"id": "2"}]`. `09-loaded-items.json` has
  `data.loadedItems = [{"id": "x"}, {"id": "y"}]`.
- **Failed element.** `10-failed-try-in-list.json` has `data.failedTryInList = null` and one error,
  `Exception while fetching data (/failedTryInList) : payload failed`. `11-failed-future-in-list.json` has
  `data.failedFutureInList = null` and one error, `Exception while fetching data (/failedFutureInList) : item failed`.
- **Controls.** `12-controls.json` has `data.plainItems = [{"id": "p1"}, {"id": "p2"}]` and `data.futureOfItems` equal to
  it.
- **Unboxer.** `run nested-wrappers-unboxer`. Exit `0`. It runs the operations of `nested-wrappers`. The errors per
  operation in `summary.txt` and every `<op>.json` match the ones of `nested-wrappers`.
- **Suspend.** `run nested-wrappers-suspend`. Exit `0`, `errors=0` for all three operations and an empty `probe.log`.
  `01-nullable-items.json` has `data.nullableItems = [{"id": "1"}, {"id": "2"}]`, `02-non-null-items.json` has the same
  list as `data.nonNullItems`, and `03-type-variable-strings.json` has `data.typeVariableStrings = ["a", "b"]`.

## Gotchas

- Before #903 (commit a4736c9a) `nested-wrappers` still exits `0`. `02`, `07`, `08` and `10` have an error per element,
  `Expected source object to be an instance of '<class>' but instead got 'scenarios.Try$Success'`, and `04`, `06`, `09`
  and `11` the same with `'java.util.concurrent.CompletableFuture'`. `03-payload-strings.json` has
  `["Success(value=a)", "Success(value=b)"]` and `05-item-names.json` has
  `java.util.concurrent.CompletableFuture@<hash>[Completed normally]` strings, both with `errors=0`.
- Before #903 `nested-wrappers-suspend` fails the build (exit `2`) with `SchemaClassScannerError: Two different classes used
  for type Item`, naming `java.util.concurrent.CompletableFuture<scenarios.NestedItem>` for `nullableItems` and
  `scenarios.NestedItem` for `nonNullItems`.
- Before #903 `nested-wrappers-unboxer` had `errors=0` for `01` to `09`, because the app's `ValueUnboxer` did the
  unwrapping. `10` and `11` were `THREW java.lang.IllegalStateException` in `summary.txt`, with `{"threw": ...}` in the
  JSON, because the failed element's exception left `execute`. Now they return `errors=1` like `nested-wrappers`.
