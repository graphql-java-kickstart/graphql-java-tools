---
name: verify-return-value-unwrapping
description: Exhaustively check, up to a bound, that the value of a resolver method, a field with no matching getter or a typed Map reaches graphql-java fully unwrapped. Futures are awaited, generic wrappers are transformed, Optional and DataFetcherResult are handled, and list order, nulls and failures are kept, for every return type nested up to 3 containers deep. Use it before pushing any change to TypeShape (the type walk, ContainerKind, the generic wrapper rules or the Unwrapper), the data fetchers in ResolverPlan, or the GenericWrapper defaults, and whenever a bug report says graphql-java got a CompletableFuture, Optional or wrapper object as a field value.
---

# Verify return value unwrapping

## The property

A user writes a resolver method, a field with no matching getter (of any visibility) or a `Map` whose type nests containers, such as
`CompletableFuture<List<Try<Payload>>>`. If the schema build accepts it, then graphql-java must get
exactly the value it returned, with every container removed. Futures are waited on and generic wrappers go
through their transformers. List order and nulls are kept. A failed future or a throwing transformer fails the field.
A `Future` that isn't a `CompletionStage` fails the field with the "can't wait on" error.

When this breaks, a production query returns `CompletableFuture@1a2b[Completed normally]` as a string, or fails with
"Expected source object to be an instance of 'Payload' but instead got 'CompletableFuture'". It only happens for the
return types and values nobody wrote a test for.

## How the check works

`src/test/resources/returnvalue/Check.java` lists every return type built from up to `--depth` (default 3) of these containers around a leaf:

| Container | Value variants (the first one is the default) |
|---|---|
| `List` | 2 elements, `null`, empty |
| `Page` (a `listCollectionWithTransformer` wrapper) | 2 elements, `null`, empty |
| `CompletableFuture` | completes with the value, `null`, fails |
| `Future` | a `CompletableFuture`, `null`, a completed `FutureTask` |
| `Later` (a `withCompletionStageTransformer` wrapper whose transformer returns its `CompletableFuture`, like `Mono::toFuture`) | holds a future of the value, `null`, holds a failing future |
| `Try` (a wrapper whose transformer returns the value or throws) | success, `null`, failure |
| `DataFetcherResult` | the value, `null`, the value plus an error |
| `Optional` | present, empty |

The leaf is `Payload` (an object type), `String`, or `Object` for a union `Item = Payload | FutureItem`. `FutureItem`
implements `Future`, so only the declared type tells a leaf from a future. Each return type runs with an all-nullable
and an all-non-null GraphQL type.

Each return type runs as three kinds of resolver, each compiled as a Java class at runtime:

| Kind | Generated resolver | Member in `ResolverPlan.kt` |
|---|---|---|
| `method` | `public <type> field()` on the query resolver | `MethodMember` |
| `property` | `public <type> field;` on the query resolver, which is also what a Kotlin `@JvmField` compiles to | `PropertyMember` |
| `map` | `extends HashMap<String, <type>>`, bound to `type Holder` and returned by `Query.holder` | `MapMember` |

The check builds each schema through the public `SchemaParser` API, which rejects about a fifth of the combinations, such as
`Optional` below the top level. For every accepted one it executes the query once for each value that makes at most
`--budget` (default 2) non-default choices. Properties and Maps use `--property-budget` (default 1), only the nullable
GraphQL type and only the first-created-first completion order, because what they add is how the value is read, and the
methods already cover the rest. When the return type holds futures, it runs each value three ways: futures already completed,
and pending futures completed after the data fetcher returns, first-created first and last-created first.

The expected result is what graphql-java returns when a plain data fetcher on the same schema returns the plain value,
or throws the failure. So graphql-java's own null handling and error format apply to both sides. When one value
holds several failures, any one of them failing the field counts as correct.

`ReturnValueUnwrappingTest` compiles `Check.java` and `Fixtures.java` at test time and runs the check, so `mvn verify`
runs it at the default bounds on every build. About 226,000 executions run in about 18 seconds on JDK 17.

## Run

From the root of the checkout or worktree you want to check:

```bash
.claude/skills/verify-return-value-unwrapping/check.sh
```

It prints the commit and the JDK, runs `mvn clean test -Dtest=ReturnValueUnwrappingTest` with the options below passed
as `-Dunwrapping.args`, and prints the report. On a failure it also prints the path of the Maven log.

| Exit | Meaning |
|---|---|
| 0 | `PASS`, or `PASS apart from the known findings above` |
| 1 | `FAIL`: a return type that isn't a known finding breaks the property. `NEW`: a known finding matches more accepted return types than its line records |
| 2 | `MODEL ERROR`: the checkout or the check didn't compile, the build rejected a return type the check must exercise, or a `ContainerKind` in `TypeShape.kt` has no `Container` in `Check.java`, so a pass would prove nothing |
| 3 | `STALE`: a known finding matches a return type that no longer fails, or fewer accepted return types than its line records. Or the run's coverage differs from `pinnedCoverage` in `Check.java` |

Options: `--depth N`, `--budget N`, `--property-budget N`, `--show N` (counterexamples and stale return types printed, default 15), and `--rejected`, which lists
every combination the schema build rejected, with the build's error. A depth below 3 can't build the `mustAccept`
return types, so it ends in `MODEL ERROR`. `NEW` and `STALE` apply only at the default depth and budgets, because the
counts in `known-findings.txt` and `pinnedCoverage` are for those. A run with other bounds checks values only.

## Pinned coverage

`pinnedCoverage` in `Check.java` records how many return types the check generates, and for each kind how many of them
the schema build rejects and how many executions run. A run at the default bounds whose counts differ is `STALE`, and the
report prints the pinned and the new counts. Without the pin, a change that makes the build reject more return types,
skips generating some shapes, or runs fewer values per return type would still print `PASS`, because nothing it stopped
running can fail.

When the counts move, diff `--rejected` output against the last commit to see which return types the build now rejects
or accepts, and confirm the change is intended. A change to `Check.java` itself moves the counts with no change in
`--rejected`. Then copy the counts the report prints into `pinnedCoverage` in the same diff, so a reviewer sees what the
check stopped or started covering. A change that rejects one return type and accepts another of the same kind, with the
same number of executions, keeps the counts and gets past the pin.

## Reading a counterexample

```
public List<Future<Payload>> field()  for  field: [Payload]   (21 failing executions, first shown)
  returns:   List[CompletableFuture(Payload(v1)), FutureTask]
  futures complete before the data fetcher returns
  expected:  {errors=[{message=Exception while fetching data (/field) : Resolver method '...' resolved to a java.util.concurrent.FutureTask, which graphql-java can't wait on. ...
  got:       {errors=[{message=Exception while fetching data (/field[1]/data) : Expected source object to be an instance of 'unwrapcheck.Fixtures$Payload' but instead got 'java.util.concurrent.FutureTask', ...
```

The first line is the resolver and the field's GraphQL type. A property prints as `public List<Future<Payload>> field;`
and a Map as `extends HashMap<String, List<Future<Payload>>>  for  Holder.field: [Payload]`. `returns` is the value the method returned.
`Later(CompletableFuture(x))` means a `Later` whose transformer returns a `CompletableFuture` of `x`. Only the first
failing value per return type is shown. Rerun with a higher `--show` to see more return types.

A real bug versus a modeling error:

- **Real bug.** `got` holds a container object (a future, `Optional`, `DataFetcherResult`, `Try`) as a value, an
  "Expected source object" error, a lost or reordered element, a missing error, or a blocked data fetcher. Report it with
  the counterexample. Don't change the check to make it pass.
- **Real bug in the schema build.** `expected` is itself nonsense, such as `Payload` bound to the `DataFetcherResult`
  class, which returned `"unwrapcheck.Fixtures$Payload@5a77273c"` as `data`. The plain data fetcher runs on the same schema, so if the build bound the GraphQL
  type to the wrong class, both sides go wrong.
- **Modeling error.** The check's own idea of the value is wrong. For example, a new container variant builds a value
  that its declared type doesn't allow, or the error message the check expects for a `FutureTask` (in `build`, case
  `FUTURE_TASK`) differs from a message the library changed on purpose. Confirm against the library's own tests in
  `src/test/kotlin/graphql/kickstart/tools/NestedGenericWrapperTest.kt` before you edit the check. A property fails a
  `FutureTask` as a `Resolver field` and a Map as a `Resolver map value`, each naming the generated class and `field`.
- **`MODEL ERROR` about rejected return types.** A change made the build reject a return type in `mustAccept` in
  `Check.java`. That's either a regression in the build, which you report, or an intended change, after which you swap in
  another accepted return type of the same kind.

## Known findings

`src/test/resources/returnvalue/known-findings.txt` lists the return types that break the property at the sync commit, one per line as
`<count> <regex> # <what goes wrong>`. The regex is matched against the kind and the short return type, such as
`property List<CompletableFuture<String>>`, so a pattern without a kind covers all three. The first matching line wins.
Matching return types still print as `KNOWN` with a counterexample on every run, and the check passes apart from them.

Each line must match only return types that fail, and exactly `<count>` of the return types the build accepts. A
matched return type that passes makes the check `STALE` and names it, so a partial fix has to narrow the line, and a later
regression in a fixed return type is a `FAIL`. A count that grows makes it `NEW`, because a return type the build used to
reject now fails under the line. What stays hidden is a known return type that starts failing in a different way, so
after a change to the code a known finding covers, read its counterexample.

At the sync commit the file is empty. Methods, properties and typed Maps unwrap their values through the same
`TypeShape.Unwrapper`, and every return type the build accepts passes.

The check found two method bugs when it was written, and both are fixed. One was a `DataFetcherResult` inside an
`Optional` (#939). The other was a wrapper whose transformer returns a future, directly around a declared future (#938). A
`withTransformer` registration keeps that #938 behavior, which is why `Later` is registered through
`withCompletionStageTransformer`.

When a fix lands, the check exits 3 and names the return types that pass. Narrow the line to the rest and set its count,
or delete it when none fail, so that a regression shows up as `FAIL`.

## What this does not cover

- Suspend functions, subscriptions and `Publisher`, arrays, and Kotlin-declared resolver methods. The generated resolvers
  are Java methods without parameters, so only `LightFetcher` in `ResolverPlan.kt` runs, not
  `Fetcher`.
- Wrappers picked by a subclass of the value (`Box` and `SpecialBox` in `NestedGenericWrapperTest`), wrappers with a type
  argument index other than 0, custom `schemaWrapper`s other than the list one, transformers that return a `FutureTask`,
  and DataLoader futures.
- Lists of more than 2 elements, more than 2 non-default choices per value, more than 3 containers, and completion
  orders other than the two listed. A pass says nothing beyond those bounds.
- Which return types the schema build should reject. The check only exercises what the build accepts. A change in how
  many it rejects fails through `pinnedCoverage`, but the check doesn't judge whether the new count is right.
- Untyped Maps, whose value type is `Object`, properties with a getter (those run as methods), non-public fields (the
  generated ones are public, and the same data fetcher reads private ones), and properties and Maps on a type below the
  root other than `Holder`. `ResolverCompatibilityTest` pins untyped Maps and Kotlin `@JvmField`s.

## Coverage and sync

Last confirmed to describe the code at commit `948c1ca6` (2026-11-06). It covers:

| File | What |
|---|---|
| `src/main/kotlin/graphql/kickstart/tools/resolver/TypeShape.kt` | the walk, `ContainerKind`, `GenericWrappers`, `resolveTypeVariables`, the `Unwrapper` and its steps |
| `src/main/kotlin/graphql/kickstart/tools/resolver/ResolverPlan.kt` | `dataFetcher` (the `TypeShape.Unwrapper` per kind), `returnShape`, the members' `declaredValueType` and `read`, `Fetcher` and `LightFetcher` |
| `src/main/kotlin/graphql/kickstart/tools/SchemaParserOptions.kt` | `GenericWrapper` and the default wrappers in `build()` |

To see whether a change needs another look:

```bash
git log --oneline 948c1ca6..HEAD -- src/main/kotlin/graphql/kickstart/tools/resolver/TypeShape.kt src/main/kotlin/graphql/kickstart/tools/resolver/ResolverPlan.kt src/main/kotlin/graphql/kickstart/tools/SchemaParserOptions.kt
```

Move the sync commit forward only after you've read those changes and confirmed that the containers, variants and
expected values in `Check.java` still describe them. For example, a new default wrapper or a new kind of unwrapping
step needs a new `Container` with its variants and its plain value in `build`. A new `ContainerKind` ends in
`MODEL ERROR` until it has one. After an intended change, rerun the check,
and break the new code on purpose once in a scratch worktree to see the check fail before you trust its pass.
