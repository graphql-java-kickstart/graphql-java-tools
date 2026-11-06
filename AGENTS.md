# graphql-java-tools

Open-source library (`io.github.graphql-java-kickstart:graphql-java-tools`) that builds a graphql-java schema from GraphQL SDL and
resolver classes. Published to Maven Central. Kotlin, with a few Java files, targeting Java 17.

## Layout

- `src/main/kotlin/graphql/kickstart/tools`: `SchemaParser` and its builder and options, `MergedDefinitions.kt` (merges each
  SDL type with its extensions into a `MergedType`. Other code, apart from the Relay factory, reads SDL types only through
  it), `SchemaClassScanner` (matches schema types to classes), `resolver/` (`ResolverPlan.kt` finds the Java member behind each field and builds its data fetcher, `TypeShape.kt` walks
  declared types and unwraps values, `ArgumentConverter.kt` converts arguments), `directive/`,
  `relay/`, `proxy/` (finds the class behind a Spring, Guice, Weld or Javassist proxied resolver), `util/`
- `src/test/kotlin`: JUnit tests, one class per feature. A test for an issue goes into the class for its feature.
  `EndToEndTest` and `EndToEndSpecHelper` hold the shared schema and resolvers
- `.claude/skills/verify-tools`: the probe that checks a change from a consuming app's side

## Build and test

```bash
mvn clean verify
```

Compiler warnings fail the build. CI runs `mvn verify` on JDK 17, 21 and 25. A newer local JDK can pass code that fails on
17, so run the build on JDK 17 before you push.

Machine-specific Maven JVM flags go in `.mvn/jvm.config` and personal agent rules go in `CLAUDE.local.md`. Git ignores both.

## Verifying

Run the `verify-tools` skill (`.claude/skills/verify-tools/SKILL.md`) before you push any change to how the schema is built.
`mvn verify` runs the library's own tests. The probe builds a schema through the public API only, the way an app does.
Bugfixes and small features stop at this skill.

## Compatibility

- Public API changes must stay backwards compatible. Deprecate before removing.
- Invalid input that used to build stays accepted until the next major. Log a WARN that names the type and the item, open a
  GitHub issue with the target major in its title (the repo has no milestones), and leave `// TODO <change> in X.0.0 (#N)`.

## GitHub

- PRs follow `.github/pull-request-template.md` and the
  [contribution guide](https://github.com/graphql-java-kickstart/graphql-java-tools/wiki/Contribution-guide). Open them as ready
  for review, not drafts. Reference the issue with `Fixes #N`.
- A follow-up to an open PR goes into that PR as another commit.
- Deferred work and out-of-scope findings become GitHub issues.
- When a PR has release-note material, add it to the PR description.

## Releases

Follow the [Releasing a version](https://github.com/graphql-java-kickstart/graphql-java-tools/wiki/Releasing-a-version) wiki
page. `github-build.sh` and `.github/workflows/release.yml` do the version bump, tag and publish. After an X.0.0 release,
delete the `<excludes>` under japicmp-maven-plugin in `pom.xml`. The version is X.0.1-SNAPSHOT by then and the API check
fails while they remain.

After the release is published, record what it builds, so `CompatTest` checks later changes against it. Check out the
release tag in a worktree, run `mvn -B -ntp clean test -Dtest=CompatTest -Dcompat.capture`, and open a PR with the
`src/test/resources/compat/<version>/` it writes: `cases.tsv` and an empty `accepted.txt`. A later break that the release
notes list goes into that `accepted.txt` as one line: the case as `CompatTest` lists it, a tab, and why, naming the issue
(`#N`) or the release notes (`X.Y.Z release notes`). Start the case with `re:` to give a regex instead, matching at most 20
cases, all in one scenario. Capturing fails at a `-SNAPSHOT` version, and so does the test while a capture exists for a version
the pom says isn't released yet, because a capture made on a branch hides the branch's breaks. A line that matches
no failing case fails the test. So does a case that built on the release and isn't generated any more, because a scenario
was renamed, removed or edited. Then capture the release again as the failure says, or list the case.

## Rules that need judgment

No check can enforce these, so each one names the mistake it prevents.

- **Build only what was asked.** Don't add support for a case nobody asked for. Ask first. Failure: a PR made the library call
  JDK proxied resolver methods through the proxy's interfaces, a shape no user had reported. A later commit replaced that
  with a warning at build and deleted 282 lines (`76b67394`).
- **Check every claim in a PR body or release note at the current head.** Re-read the body after each push. A claim about
  what users do needs an issue comment or a consumer's code behind it. Failure: a PR body said existing tests already
  proved scalars pass through unchanged, and none of them did.
- **Add a variant to the existing test class and its shared fixtures.** Write one test per behavior and cover variants with
  a table or a generated matrix, as `NestedGenericWrapperTest` does. Failure: a directive change added a test and
  fixtures per variant. A later commit folded five of the failure tests into one `assertSchemaError` test and dropped two
  that repeated other tests (`f8c7be76`).
- **Run verify-tools on refactors and warning changes too.** A change called "a refactor" or "only the wording" still
  changes what an app's schema build does, and the unit tests don't build through the public API alone. Failure: an agent
  skipped the probe on a scanner rewrite because it was a refactor.
- **Keep a comment only for a non-obvious why.** No comment that repeats the code, narrates history, or carries an issue or
  PR number outside a TODO. `CommentConventionsTest` catches issue numbers and the history phrases it lists. A comment that
  repeats the code, or tells history in other words, gets past it. Failure: a comment explained what `determineType`
  returned, which the `namedType` default already showed, and a follow-up commit deleted it (`7448f2c6`).

## Rule table

Each rule comes from a mistake made in this repo more than once, mostly by agents and in a few cases by older human code.
The second column names what enforces the rule: a check that fails the build, or code or config that keeps the mistake from
happening. "docs only" means nothing does yet, so the rule depends on you reading it.

| Rule | Enforced by |
|---|---|
| The value of a resolver method, a field with no matching getter or a typed Map is unwrapped by one rule that the schema build and the data fetcher share | `TypeShape`, whose one walk yields both the class the schema build matches and the layers that `TypeShape.Unwrapper` unwraps in the method, field and Map data fetchers. An untyped Map's values reach graphql-java as they are. A container kind can't be added without its placement rule and its value step, since both are abstract on the enum, and `ReturnValueUnwrappingTest` fails until the bounded check builds values for it |
| A resolver method's parameter layout, type variables and source are decided once, at schema build | `ResolverPlan`, which reads the field's member once and hands the scan its type shapes, the data fetcher its parameters and `UnmappedInputFieldChecker` its argument targets. Its member variants are private to `ResolverPlan.kt` and expose a raw read, never a data fetcher. A new `ResolverKind` doesn't compile until the lookup's `when` handles it, a new `ExtraParameter` doesn't compile without its binder, and the fixture tests in `ResolverCompatibilityTest` don't compile until they build a sample of each. `ResolverOwnershipTest` fails on member reflection outside `ResolverPlan.kt` (apart from the input-field getters in `ScanDictionary.findInputValueTypeInType`), on a member that depends on a data fetcher, on a data fetcher without a `TypeShape.Unwrapper`, and on generic-wrapper or `DataFetcherResult` handling outside `TypeShape.kt` |
| A change to how resolvers are found, bound or unwrapped keeps what a method, a property and a Map field return | `ResolverCompatibilityTest`, which compares field and Map values with a method's, and the bounded check in `ReturnValueUnwrappingTest`, which compares all three with the plain value. Neither has known findings, so a value that differs on a return type they run fails the build. The bounded check also pins how many return types it generates, and for each kind how many the build rejects and how many executions run, so a change that stops running some fails until the same diff updates `pinnedCoverage`. It doesn't judge whether a newly rejected return type should build |
| One output has one builder. Don't add a second path that builds the same thing | docs only |
| Read an SDL type's members merged with its extensions, never from the raw definition | `MergedType` in `MergedDefinitions.kt` and `DefinitionsBoundaryTest`, which fails on a raw member, directive or child-node read, an extension class, an SDL node walk or a `TypeDefinitionRegistry` call outside it. A read by reflection gets past it. `TypeExtensionTest` builds and queries a schema with an extension of every kind, so a build that leaves an extension's fields, interfaces, members, values, input fields or directives out of the built type fails it, however the code read them |
| Every kind of SDL type starts its graphql-java type from the same recipe | `MergedType.prepare`, `DefinitionsBoundaryTest` and `TypeKindRecipeTest` |
| Reuse an existing structure before you add a parallel one | docs only |
| Argument conversion goes through one entry point | `ArgumentConverter`, which `ResolverPlan` builds once per argument. `ResolverOwnershipTest` fails on a TokenBuffer or a Jackson `readValue`, `convertValue`, `treeToValue` or `valueToTree` outside `ArgumentConverter.kt`, and on an `ArgumentConverter` built or called outside `ResolverPlan.kt`. Jackson's per-call retry for scalars it can't convert stays inside `ArgumentConverter` |
| An input field's Java type comes from the mapping Jackson uses, not from guessed getters | docs only |
| Every recursive walk over the type graph handles cycles, and a test covers a cycle | docs only |
| Public signatures stay binary compatible, data class `copy()` and `componentN` included | japicmp with `build/japicmp-kotlin-public.groovy` at `mvn verify`, which allows excludes and other narrowing options only at an X.0.0 version |
| Remove the plumbing a change leaves unused | Compiler warnings for unused locals, docs only for the rest |
| A new warning or build error names the type and the item and uses `SourceLocation.describe()` for its location | docs only |
| A check keyed on a class name or an exception type has a test that fails when the dependency moves it | docs only |
| Work that depends only on the schema happens at schema build, not on every fetch | docs only |
| Schema build time grows linearly with the schema | docs only |
| A subtype check has a test with a subclass, so an inverted check fails | docs only |
| Directive wirings decide which types become `GraphQLTypeReference` in one place | `TypeReferences` in `DirectiveWiringHelper` |
| An object built once at schema build holds no per-request state | docs only |
| Add a variant to the existing test class and its shared fixtures | docs only |
| Every test class in `src/test` runs, and so do its assertions | surefire's `**/*` include, so a class runs whatever its name, and `failIfNoTests`, which fails a run that executes no tests. The Kotlin test compile's `-Xassertions=always-enable` makes `assert()`, which `TestUtils.assertEquals` uses, run without `-ea`. `-DfailIfNoTests=false` turns the empty-run check off, and a test that asserts nothing still passes |
| A new test fails without the change it guards | docs only |
| A fix keeps every shape that worked on the last release | docs only |
| A test that builds a schema also queries its fields with `assertEveryFieldResolves` | docs only |
| The first commit of a fix tests the neighbouring shapes, not just the reported one | For return values, the bounded check in `ReturnValueUnwrappingTest` at `mvn verify`, which builds every nesting of up to 3 containers around each leaf type, for methods, properties and Map fields. Docs only for other shapes |
| The build passes on JDK 17, the lowest supported version | CI on JDK 17, 21 and 25 |
| Run verify-tools before you push a schema-building change | docs only |
| The packaged jar holds `module-info.class`, its non-static `requires` resolve from the runtime dependencies, and every class targets the pom's `java.version` | `build/JarCheck.java` at `mvn verify` |
| Compiler warnings fail the build | `-Werror` and `failOnWarning` in `pom.xml` |
| Check every claim in a PR body or release note at the current head | docs only |
| Keep a comment only for a non-obvious why, with no history and no issue numbers outside a TODO | `CommentConventionsTest`, which reads each comment block in the `.kt` and `.java` files under `src/main` and `src/test` and fails on an issue number or issue link outside `// TODO <change> in X.0.0 (#N)`, a history phrase from its list, or a version such as `@since` or "since 1.2.0". `lint-allow <rule> until YYYY-MM-DD by <name>: <reason>` on the line skips that rule there, for at most 90 days. History in words the list doesn't have, and a comment that repeats the code, are docs only |
| Build only what was asked | docs only |
| A build you trust starts from an empty `target/`, so no class from an earlier build ships | `clean` in `probe.sh install`, docs only for your own `mvn` runs |
| Agent worktrees and local settings stay out of commits | `.gitignore` |
| Agent rules live in one committed file | This file. `CLAUDE.md` imports it |
| Invalid input that built on the last release keeps building until the next major | `CompatTest`, against the last release's results in `src/test/resources/compat` |
