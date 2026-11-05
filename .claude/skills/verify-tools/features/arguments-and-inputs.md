# Arguments and input types

Resolver methods take GraphQL arguments as ordinary parameters, in SDL order. graphql-java-tools converts scalars, enums,
lists and input objects into the parameter's declared class, applies SDL default values, and passes
`DataFetchingEnvironment` when it is the last parameter. An input object can refer to itself, or to another input object
that refers back. With `inputArgumentOptionalDetectOmission`, an `Optional` parameter tells an omitted argument (`null`)
from an explicit `null` (`Optional.empty()`).

## Sub-features

- `args-defaults` applies an argument's SDL default when the operation omits it.
- `args-enum-list` converts `[Genre!]` literals into a `List<Genre>` of a Kotlin enum.
- `args-env` passes `DataFetchingEnvironment` as the trailing parameter.
- `args-input-object` maps a nested input object into data classes, including input field defaults.
- `args-optional-omission` passes `null` for an omitted argument, `Optional.empty()` for an explicit null, and
  `Optional.of` for a value.
- `args-invalid-enum` rejects an unknown enum literal during validation, before any resolver runs.
- `args-unmapped-warn` logs a WARN at build for an input field the argument's class has no property for, here a
  misspelled property under an `Optional`.
- `args-unmapped-ignored` doesn't report a field the property's `@JsonIgnoreProperties` ignores, here on an `Optional`
  property.
- `args-type-variable` converts an argument whose parameter is typed by the resolver superclass's type variable into the
  class the subclass binds it to.
- `args-scalars` passes a custom scalar's coerced object through unconverted, also when it's a Kotlin class without visible
  properties inside an input object, converts `ID` into a `Long` parameter, and converts `[[String]]` into a
  `List<Set<String>>` parameter.
- `args-recursive` converts an input object that refers to itself, and two that refer to each other, through lists, nested
  lists and non-null types into data classes at every depth. It also builds a directive whose arguments are an input
  object, an enum with a default and a list.

## Expected results

- **Run.** `run inputs`. Exit `0`. `summary.txt` shows `errors=0` for `01` to
  `03` and `errors=1` for `04-invalid-enum`.
- **Defaults, enums, env.** `01-search.json` has
  `data.defaults = "term=dune limit=10 genres=null field=search"` and
  `data.explicit = "term=dune limit=2 genres=[SCIENCE, HISTORY] field=search"`.
- **Omitted vs null.** `02-nickname.json` has `omitted = "omitted"`, `explicitNull = "explicit null"` and
  `given = "value Bilbo"`.
- **Input objects.** `03-add-book.json` (variables in `03-add-book.variables.json`) has `data.addBook` equal to
  `{"title": "Cosmos", "genre": "SCIENCE", "tags": ["space", "classic"], "pages": 396, "language": "en"}`. `language`
  comes from the input field default. `data.defaults` is `{"genre": "FICTION", "tags": [], "pages": null}`.
- **Invalid enum.** `04-invalid-enum.json` has no `data`, and one error whose message starts with
  `Validation error (WrongType@[search])` and names `POETRY`.
- **Unmapped input fields.** `run unmapped-inputs`. Exit `0`. `probe.log` has
  exactly one `UnmappedInputFieldChecker` WARN, starting `Input field 'ItemInput.name' has no matching property in
  graphql.kickstart.tools.compat.scenarios.ItemInput` and listing `Known properties: nmae`. There is no WARN for
  `InnerInput.legacy`. `01-ignored.json` has `data.ignored = "a"`. `02-typo.json` has one error containing
  `Unrecognized property "name" (class graphql.kickstart.tools.compat.scenarios.ItemInput)` (`Unrecognized field` before
  Jackson 3, in 14.x), which shows the reported field really fails.
- **Type variable parameters.** `run generic-input-resolver`. Exit `0`.
  `summary.txt` shows `errors=0` for both operations. `02-generic.json` has
  `data.generic = "generic got GreetingInput(name=a)"`, and `01-plain.json` has
  `data.plain = "plain got GreetingInput(name=a)"`.
- **Scalar arguments.** `run scalar-args`. Exit `0`. `summary.txt` shows
  `errors=0` for the first five operations. `01-custom-scalar.json` has `data.tag = "tag kotlin"` and
  `data.tags = "tags [a, b]"` (`Tag` has no constructor Jackson can use, so this only works when the value is passed
  through). `02-id.json` has `data.id = "id 43 (long)"`. `03-nested-sets.json` has
  `data.nestedSets = "sets [[a, b], [c]] sizes [2, 1]"`. `04-nested-lists.json` has
  `data.nestedLists = "lists [[a, a, b], [c]]"`. `05-empty-kotlin-scalar.json` has `data.tokenInput = "token input"`.
  `06-marked-empty-kotlin-scalars.json` has one error and `07-optional-marked-empty-kotlin-scalars.json` two, all containing
  `missing type id property '@class'` or `Cannot deserialize value of type`. Those scalars fail rather than reach the resolver
  as a default instance.
- **Recursive inputs.** `run recursive-inputs`. Exit `0`, `errors=0` for both operations and an empty `probe.log`.
  `01-describe.json` (variables in `01-describe.variables.json`) has `data.names = ["a", "b"]`. `data.describe` starts
  `TreeFilter(name=root, and=[TreeFilter(name=a,` and ends `siblings=[[ChildFilter(parent=null, siblings=null, depth=4)],
  []], depth=3))`. `data.literal` is `TreeFilter(name=x, and=null, not=null, child=ChildFilter(parent=null,
  siblings=[[ChildFilter(parent=null, siblings=null, depth=2)]], depth=1))`. `schema.graphqls` has `siblings:
  [[ChildFilter!]!]` and `directive @limit(labels: [String!], range: RangeInput!, unit: LimitUnit = ITEMS) on
  FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION`. In `02-introspect.json`, `limit` has `range` of
  `RangeInput!`, `unit` of `LimitUnit` with default `ITEMS`, and `labels` of `[String!]`.

## Gotchas

- Arguments bind by position, not name. A resolver whose parameters are in a different order than the SDL still builds
  when the types line up, and returns swapped values.
- Without `inputArgumentOptionalDetectOmission`, an omitted argument and an explicit null both arrive as
  `Optional.empty()`. This scenario turns the option on. Prove the default behavior in a separate scenario.
- `04-invalid-enum` fails in graphql-java validation. It proves the generated enum type, not the resolver's conversion.
- The unmapped input field check comes from PR #857. On a checkout without it, `unmapped-inputs` still exits `0` with the
  same JSON, but `probe.log` has no `UnmappedInputFieldChecker` lines.
- `02-typo` errors because the scenario uses the default `ObjectMapper`, which fails on unknown properties. It doesn't
  cover a mapper that ignores them.
- The type variable fix comes from PR #882. On a checkout without it, `generic-input-resolver` still builds and exits
  `0`, but `02-generic.json` has `data: null` and an error containing `TypeVariableImpl cannot be cast to class
  java.lang.Class`.
- The nested list fix comes from PR #891. On a checkout without it, `scalar-args` still exits `0`, but
  `03-nested-sets.json` has an error containing `ArrayList cannot be cast to class java.util.Set`.
- The fix for a Kotlin scalar class without visible properties comes from issue #926. On a checkout without it,
  `05-empty-kotlin-scalar.json` has `data.tokenInput = "token default"`, since Jackson wrote the value as `{}` and read it
  back as a new instance. The same scalar written with a type id or an object id comes from issue #932. On a checkout
  without it, `07-optional-marked-empty-kotlin-scalars.json` has no errors and `"typed default"` and `"id default"`. The probe doesn't cover an `ObjectNode` parameter, which needs Jackson 3 on the probe's compile
  path. `CustomScalarInputTest` does.
- `recursive-inputs` registers no directive wiring. `wiring-shapes` in `directives-and-relay.md` covers wirings on a
  recursive input.
