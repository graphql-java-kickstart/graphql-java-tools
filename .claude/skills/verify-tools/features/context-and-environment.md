# Context and environment parameters

After its schema arguments, a resolver method can take any of `DataFetchingEnvironment`, `graphql.GraphQLContext` and the
class set with `contextClass`, in any order. The custom context is read from the `GraphQLContext` map under its class.
Any other trailing parameter type fails the build with "No method found".

## Sub-features

- `ctx-env-both` passes the custom context and the environment to `greeting(name, context, env)`.
- `ctx-env-reversed` passes them to `farewell(name, env, context)`.
- `ctx-env-type-resolver` passes `graphql.GraphQLContext` and the environment to a `GraphQLResolver<AppUser>` method.
- `ctx-env-single` keeps a single environment or a single context parameter working.
- `ctx-env-invalid` rejects a trailing `String` parameter at build time.
- `ctx-env-misbound` warns at build when a schema argument lands on an environment or context parameter.
- `sourceless` binds a field resolver method that leaves out the source and takes only arguments, the environment or the
  context, when no method with the source and no property matches.

## Expected results

- **Run.** `run context-and-environment`. Exit `0`, `01-query: errors=0`.
- **Results.** `01-query.json` has `data = {"greeting": "greeting: hi ada, from oryan", "farewell": "farewell: bye ada,
  from oryan", "user": {"name": "oryan", "locale": "locale of oryan: en-CA"}, "environmentOnly": "environmentOnly",
  "contextOnly": "en-CA"}`.
- **Invalid.** `run context-and-environment-invalid`. Exit `2`.
  `build-error.txt` starts with `graphql.kickstart.tools.resolver.FieldResolverError: No method found ... (optionally
  followed by any of [interface graphql.schema.DataFetchingEnvironment, class graphql.GraphQLContext, class
  scenarios.AppContext])`.
- **Misbound.** `run context-misbound`. Exit `0`. `probe.log` has 3 `SchemaClassScanner` WARNs, for `Query.users`,
  `Query.contextArgument` and `User.thumbnail`, none for `Query.greeting`. The first reads `Field 'Query.users' has no
  parameter for its argument 'first: Int' in scenarios.ContextMisboundQuery.users, so the DataFetchingEnvironment
  parameter gets the argument instead.` All four operations have `errors=1`. `01` fails with `Parameter specified as
  non-null is null`, `02` and `04` with ``Cannot construct instance of `graphql.schema.DataFetchingEnvironment` ``, and
  `03-context-and-correct.json` has `data = {"contextArgument": null, "greeting": "greeting: hi ada"}` with one error, for
  `/contextArgument`. Before #890 the same run has the same four errors and an empty `probe.log`.
- **Without the source.**
  - `run sourceless`: exit `0`, `errors=0` for both operations. In `01-local-context.json` each image has
    `imageBlob = "blob for [png, jpg]"` (`imageBlob(env)` reads the `localContext` that `images` returned),
    `thumbnail = "png@64"` and `caption = "caption of cat"` (`dog` for the second). In `02-contexts.json` each image has
    `owner = "ada"`, `locale = "en-CA"` and `signature = "signature by oryan"`.
  - `run sourceless-shadowed`: exit `0`. `01-shadowed.json` has `name = "cat"` and `title = "with source cat"`, so the
    source's `name` property wins over `getName(env)` and `title(image)` over `title(env)`.
  - `run sourceless-unimplemented`: exit `0`. `probe.log` has `Missing resolver for field: FieldDefinition{name='preview'`
    next to the development-only warning for `allowUnimplementedResolvers`, so `preview(env)` is not bound. `execute`
    throws, so `01-helper.json` is `{"threw": "java.util.concurrent.CompletionException: kotlin.NotImplementedError: An
    operation is not implemented: Schema resolver not implemented"}`.
  - `run sourceless-misbound`: exit `2`. `thumbnail(GraphQLContext, env)` for `thumbnail(size: Int)` still fails the build.
    The signatures listed for `scenarios/sourceless-misbound/schema.graphqls:11` end with
    `scenarios.MisboundImageResolver.thumbnail(~size, <environment or context>)` and
    `scenarios.MisboundImageResolver.getThumbnail(~size, <environment or context>)`.

## Gotchas

- Before #888 only one trailing parameter was allowed, so `context-and-environment` fails to
  build on older versions with the same "No method found" error.
- Before #904 `sourceless` fails the build (exit `2`) with `No method or field found ... imageBlob` and six listed
  signatures, none ending in `<environment or context>`. `sourceless-shadowed` and `sourceless-unimplemented` give the
  same results as after it. `sourceless-misbound` still fails the build, but its error lacks the two
  `(~size, <environment or context>)` signatures.
