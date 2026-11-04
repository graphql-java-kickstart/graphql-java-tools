# graphql-java-tools

Open-source library (`io.github.graphql-java-kickstart:graphql-java-tools`) that builds a graphql-java schema from GraphQL SDL and
resolver classes. Published to Maven Central. Kotlin, with a few Java files, targeting Java 17.

## Layout

- `src/main/kotlin/graphql/kickstart/tools`: `SchemaParser` and its builder and options, `SchemaClassScanner` (matches schema
  types to classes), `resolver/` (field resolvers and data fetchers), `directive/`, `relay/`, `util/`
- `src/test/kotlin`: JUnit tests, one class per feature or issue. `EndToEndTest` and `EndToEndSpecHelper` hold the shared schema
  and resolvers
- `.claude/skills/verify-tools`: the probe that checks a change from a consuming app's side

## Build and test

```bash
mvn verify
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
page. `github-build.sh` and `.github/workflows/release.yml` do the version bump, tag and publish.

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
  fixtures per variant, and a later commit folded seven of those tests into one (`f8c7be76`).
- **Run verify-tools on refactors and warning changes too.** A change called "a refactor" or "only the wording" still
  changes what an app's schema build does, and the unit tests don't build through the public API alone. Failure: an agent
  skipped the probe on a scanner rewrite because it was a refactor.
- **Keep a comment only for a non-obvious why.** No comment that repeats the code, narrates history, or carries an issue or
  PR number outside a TODO. Failure: a comment explained what `determineType` returned, which the `namedType` default
  already showed, and a follow-up commit deleted it (`7448f2c6`).

## Rule table

Each rule comes from a mistake agents made in this repo more than once. The second column names what fails when the rule
is broken. "docs only" means nothing does yet, so the rule depends on you reading it.

| Rule | Enforced by |
|---|---|
| A resolver method's return value is unwrapped by one rule that the schema build and the data fetcher share | docs only |
| A resolver method's parameter layout, type variables and source are decided once, at schema build | docs only |
| One output has one builder. Don't add a second path that builds the same thing | docs only |
| Read an SDL type's members merged with its extensions, never from the raw definition | docs only |
| Reuse an existing structure before you add a parallel one | docs only |
| Argument conversion goes through one entry point | docs only |
| An input field's Java type comes from the mapping Jackson uses, not from guessed getters | docs only |
| Every recursive walk over the type graph handles cycles, and a test covers a cycle | docs only |
| Public signatures stay binary compatible, data class `copy()` and `componentN` included | docs only |
| Remove the plumbing a change leaves unused | Compiler warnings for unused locals, docs only for the rest |
| A new warning or build error names the type and the item and uses `SourceLocation.describe()` for its location | docs only |
| A check keyed on a class name or an exception type has a test that fails when the dependency moves it | docs only |
| Work that depends only on the schema happens at schema build, not on every fetch | docs only |
| Schema build time grows linearly with the schema | docs only |
| A subtype check has a test with a subclass, so an inverted check fails | docs only |
| Directive wirings decide which types become `GraphQLTypeReference` in one place | `TypeReferences` in `DirectiveWiringHelper` |
| An object built once at schema build holds no per-request state | docs only |
| Add a variant to the existing test class and its shared fixtures | docs only |
| A new test fails without the change it guards | docs only |
| A fix keeps every shape that worked on the last release | docs only |
| A test that builds a schema also queries its fields with `assertEveryFieldResolves` | docs only |
| The first commit of a fix tests the neighbouring shapes, not just the reported one | docs only |
| The build passes on JDK 17, the lowest supported version | CI on JDK 17, 21 and 25 |
| Run verify-tools before you push a schema-building change | docs only |
| The packaged jar matches the pom's module and Java target | docs only |
| Compiler warnings fail the build | `-Werror` and `failOnWarning` in `pom.xml` |
| Check every claim in a PR body or release note at the current head | docs only |
| Keep a comment only for a non-obvious why, with no history and no issue numbers outside a TODO | docs only |
| Build only what was asked | docs only |
| Agent worktrees and local settings stay out of commits | `.gitignore` |
| Agent rules live in one committed file | This file. `CLAUDE.md` imports it |
| Invalid input that used to build logs a WARN until the next major | docs only |
