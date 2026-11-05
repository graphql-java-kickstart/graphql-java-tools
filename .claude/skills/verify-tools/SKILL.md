---
name: verify-tools
description: Prove a graphql-java-tools behavior the way a consuming app sees it. Installs this checkout into a private Maven repo, builds a schema from SDL files and resolver classes in a separate probe project through the public API, runs GraphQL operations, and saves the printed schema, JSON results and library warnings as evidence. Use to confirm a fix or feature works from the outside (not just in the library's own unit tests), or to reproduce an issue report. Run it before pushing any schema-building change to an open PR, including refactors and new or changed library warnings, whose only proof is the probe's log.
---

# Verify graphql-java-tools from a consumer

`probe/` is a separate Maven project that depends on the jar installed from a checkout, so it sees only the public API,
unlike `src/test`. `mvn verify` still runs for every change. This skill proves one behavior end to end in minutes, and is
where bugfixes and small features stop.

## Run

`probe.sh` sits next to this file and verifies the checkout of the current directory. Every clone and worktree has its
own copy, so run it from the checkout root. To verify a commit that predates this skill, check it out in its own worktree
and run another checkout's `probe.sh` from inside it.

```bash
.claude/skills/verify-tools/probe.sh doctor                                 # checkout, branch, install state, lock
.claude/skills/verify-tools/probe.sh list                                   # scenario names
.claude/skills/verify-tools/probe.sh install                                # clean-build the checkout into the private repo
.claude/skills/verify-tools/probe.sh run <scenario> [--graphql-java <ver>]  # build the schema, run every operation
.claude/skills/verify-tools/probe.sh clean                                  # remove this checkout's work dir, keep evidence
```

- `run` first installs the checkout into a private Maven repo under `$TMPDIR/verify-tools/work/<checkout>-<path hash>/` when
  the install is missing or stale, meaning HEAD or uncommitted `pom.xml`/`src/` changes differ from the last install. It
  reads `~/.m2` but never writes it. The install runs `mvn clean install`, so it empties the checkout's `target/`. Don't
  run it while another build uses the same checkout. The work dir is keyed on the checkout's path, so clones and worktrees
  run in parallel, even when two share a folder name. If `doctor` shows the lock held by a live pid, wait for that run.
- It builds the schema from the scenario's files, resolvers and `configure`, prints it, then executes every `*.graphql` in
  `src/test/resources/compat/scenarios/<name>/` in name order, with `<op>.variables.json` as variables. It collects
  subscription events until the stream completes, for at most 10 seconds.
- Exit `0`: the schema built and every operation ran, though operations can still return GraphQL errors. `2`: the build
  threw. `1`: harness failure (install log or `maven.log`) or a held lock. `64`: bad usage, such as a missing or unknown
  scenario.
- `--graphql-java` swaps only the runtime graphql-java, like a user who pins another version. It doesn't recompile the
  library against it. `--skip-install` runs a stale install on purpose and doesn't count as proof.

## Evidence

`run` prints its evidence directory, `$TMPDIR/verify-tools/evidence/<timestamp>-<scenario>[-gj<version>][-<n>]/`, on its last
line.

| File | Holds |
|---|---|
| `summary.txt` | Jars actually loaded, Java version, unused definitions, build result, errors per operation |
| `run.txt` | Checkout, commit, source fingerprint and install stamp, command, exit code |
| `schema.graphqls` | The executable schema as graphql-java prints it, generated types included |
| `<op>.json` | The spec-shaped result, `events` for a subscription, or `threw` when `execute` threw |
| `probe.log` | WARN and above, plus INFO from `graphql.kickstart.tools`, where the library's build warnings land |
| `inspect.txt` | What the scenario's `inspect(schema)` returned |
| `build-error.txt` | Stack trace when the build threw |

Proof standards:

- `summary.txt`'s `graphql-java-tools jar` is inside the work dir, and `run.txt`'s fingerprint equals its install stamp.
  Otherwise you tested another build.
- Quote the operation and the relevant part of `<op>.json`. Cite `schema.graphqls` for schema shape, `probe.log` for
  warnings, and `build-error.txt` with exit `2` for build failures.
- For a regression, a new warning or a clearer error, run the same scenario on the fix and on `master` (or the commit
  before the fix), in two worktrees, and show both. They differ in exactly the way the issue describes.
- Report a sub-feature you didn't run as skipped, with the reason. Another scenario doesn't cover it.
- An expected value in `features/` that stops matching is a finding to report, not something to edit away.

## Adding a scenario

Scenarios live in the library's tests, so `mvn verify` compiles them and `CompatTest` builds each one, plus invalid
variants of it, against the last release's results.

1. Write the SDL as `src/test/resources/compat/scenarios/<name>/schema.graphqls`.
2. Add `object <Name> : Scenario` with `name`, `files` and `resolvers` in
   `src/test/kotlin/graphql/kickstart/tools/compat/scenarios/<Name>.kt`, and override `configure(builder)` for scalars,
   dictionary, directives or options. Being an object in that package registers it. Classes share the package, so prefix
   resolver and data classes with the scenario. Use only public API and nothing from other test packages, since the probe
   compiles this directory alone. If the probe needs `internal` or test classes, a user can't reach the behavior either,
   so report that.
3. Add operations as `NN-<what>.graphql`, plus `NN-<what>.variables.json` when they take variables. graphql-java rejects
   a second `__type` in one operation and still exits `0`, so read several types through `__schema { types { ... } }`.
4. Override what the scenario needs. `operations` reuses another scenario's operation directory. `schema(parser)` takes
   another build path such as `toReadOnlySchema()`. `inspect(schema)` returns evidence the printer doesn't show, and
   `dumpDirectives(schema)` lists every element's applied and legacy directives. `graphQL(builder)` and
   `input(builder)` add instrumentation, context or data loaders.
5. A scenario that doesn't compile breaks every checkout's run, so finish it before running. Keep a scenario that proves
   a lasting feature and add its exact expected results to the matching file in `features/`. Delete a one-off
   reproduction after reporting.

`features/README.md` maps each feature to its scenarios and expected results. Delete evidence directories by hand once
reported.
