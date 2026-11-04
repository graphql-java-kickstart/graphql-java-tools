# Directives and relay connections

SDL directives reach the user's code in three ways. A named `SchemaDirectiveWiring` registered with `.directive(name, …)`
can wrap the data fetcher of every field that carries the directive. `@deprecated(reason:)` shows up in introspection. A
field marked `@connection(for: "Member")` gets its `MemberConnection`, `MemberConnectionEdge` and `PageInfo` types
generated, so the SDL never declares them, and a resolver returning `graphql.relay.Connection<Member>` fills them. A
wiring also runs on interfaces, unions, enums and input objects, including ones that refer to themselves or each other.
The build checks every applied directive against its definition. It logs a WARN with the source line for an invalid
application, and fails for a directive with no definition unless `allowUndeclaredDirectives(true)` is set.

## Sub-features

- `dir-wiring-field` uppercases `@uppercase` fields on `Query` and on a nested type (`Member.name`) through a wrapped
  data fetcher.
- `dir-wiring-scope` leaves fields without the directive unchanged.
- `dir-deprecated` reports `isDeprecated` and `deprecationReason` through `__type(...).fields(includeDeprecated: true)`.
- `dir-connection-types` generates `MemberConnection`, `MemberConnectionEdge` and `PageInfo` from `@connection`.
- `dir-connection-paging` returns edges with cursors and `pageInfo` from `SimpleListConnection` for `first: 2`.
- `dir-scalar` applies the directives of a scalar and its extension, runs `onScalar`, and reports `specifiedByURL` from
  the SDL `@specifiedBy` or else from the provided scalar (`scalar-directives`).
- `dir-scalar-undeclared` logs a WARN for an undeclared directive on a scalar and builds the scalar without it
  (`scalar-directives`).
- `dir-wiring-recursive` hands a wiring on a field of a self-referencing input object the built `GraphQLInputObjectType`,
  not a `GraphQLTypeReference` (`wiring-shapes`).
- `dir-wiring-shapes` runs wirings on an interface, a union, an enum and a self-referencing object type, and lets a wiring
  make a self-referencing field non-null (`wiring-shapes`).
- `dir-applied-legacy` lists every applied directive, repeats and undeclared ones included, in `appliedDirectives`, and
  only the declared ones in the legacy `directives`, keeping the first of a non-repeatable directive
  (`directive-drift`).
- `dir-undeclared-fails` fails the build for an applied directive with no definition unless
  `allowUndeclaredDirectives(true)` is set (`directive-drift-undeclared`).
- `dir-invalid-warn` logs a WARN with the source line and column for a directive applied where its definition doesn't
  allow it, without a required argument, or twice when it isn't repeatable, and still builds (`directives-invalid`).

## Expected results

- The members are `m1` "ada", `m2` "grace", `m3` "barbara", in that order.

- **Run.** `run directives`. Exit `0`, `errors=0` for all three operations, and
  an empty `probe.log`.
- **Wiring.** `01-uppercase.json` has `data.motto = "KEEP IT SIMPLE"` and `data.oldMotto = "move fast"`.
- **Deprecated.** `02-deprecated.json` lists `motto` and `members` with `isDeprecated: false`, and `oldMotto` with
  `isDeprecated: true` and `deprecationReason: "Use motto"`.
- **Connection.** `03-connection.json` has two edges, `{"id": "m1", "name": "ADA"}` and `{"id": "m2", "name": "GRACE"}`,
  with non-empty cursors. `pageInfo` has `hasNextPage: true`, `hasPreviousPage: false`, `startCursor` equal to the first
  edge's cursor, and `endCursor` equal to the second's.
- **Generated types.** `schema.graphqls` has `type MemberConnection { edges: [MemberConnectionEdge] pageInfo: PageInfo }`
  and `type MemberConnectionEdge { cursor: String node: Member }`, and the SDL file declares neither.
- **Scalars.** `run scalar-directives`. Exit `0` and `errors=0` for both
  operations. `schema.graphqls` has `"A calendar date, wired with @since(2020-01-01), wired with @unit(day)"` above
  `scalar Date @since(date : "2020-01-01") @unit(name : "day") @specifiedBy(url : "https://example.com/date")`, and
  `scalar DateTime @specifiedBy(url : "https://scalars.graphql.org/andimarek/date-time")`. In `01-introspect-scalars.json`,
  `Date` has `specifiedByURL: "https://example.com/date"` and `DateTime` has
  `"https://scalars.graphql.org/andimarek/date-time"`. `probe.log` has exactly `Scalar 'Money' is built without its
  directives from the schema: Found applied directive currency without corresponding directive definition.`
- **Wirings on cycles.** `run wiring-shapes`. Exit `0`, `errors=0` for both operations and an empty `probe.log`.
  `schema.graphqls` has `"sees GraphQLInputObjectType TreeFilter"` on `TreeFilter.children`, `friend: Person! @required`
  and `state: State! @required` where the SDL declares both nullable, and `"described"` above `Named`, `Someone`,
  `Person`, `State` and `TreeFilter`. `01-trees.json` has `data.trees = 4`. `02-person.json` has
  `data.person = {"name": "ada", "friend": {"name": "ada"}, "state": "ALIVE", "someone": {"name": "ada"}}` and
  `data.someone = {"name": "grace"}`.
- **Applied and legacy lists.** `run directive-drift`. Exit `0` and `schema build: OK`, with no operations. In
  `inspect.txt`, `input field Meta.owner` has two `applied @once` (`reason` `"x"`, then `"because"`) and one `legacy @once`
  with `reason = "x"`. `field Query.search` has two `applied @undeclared`, the first with arguments typed from their
  literals (`i: Int`, `f: Float`, `b: Boolean`, `l: [Int]`), and no legacy one. `enum value Color.BLUE` has
  `applied @deprecated` and `applied @undeclared` but only `legacy @deprecated`.
- **Drift warnings.** `probe.log` of `directive-drift` has 12 `SchemaParser` WARNs. Six are `isn't repeatable but is applied
  more than once`, two are an extension of `Date` or `Query` applying `@once` again, two are `is missing its required
  argument` (`@tag` and `@needs`), one is `Directive @fieldOnly at line 38, column 28 in
  scenarios/directive-drift/schema.graphqls is applied to ARGUMENT_DEFINITION, but it's declared on FIELD_DEFINITION.`, and
  one is `Scalar 'Money' is built without its directives from the schema: Found an unexpected directive argument tag#nam .`
  None names `@undeclared`, because `directive-drift` sets `allowUndeclaredDirectives(true)`.
- **Undeclared.** `run directive-drift-undeclared`. Exit `2`. `summary.txt` has `schema build: FAILED
  graphql.kickstart.tools.SchemaError: Found applied directive undeclared without corresponding directive definition.`,
  and `build-error.txt` starts with the same message.
- **Invalid applications.** `run directives-invalid`. Exit `0` and `errors=0`. `01-query.json` has
  `data.motto = "KEEP IT SIMPLE"`, so the misplaced `@uppercase` still wires. `probe.log` has exactly 5 `SchemaParser`
  WARNs, each naming its line and column in `scenarios/directives-invalid/schema.graphqls`.
  `Directive @uppercase at line 12, column 20 ... is applied to FIELD_DEFINITION, but it's declared on OBJECT.`
  `Directive @deprecated at line 17, column 13 ... is applied to OBJECT, but it's declared on FIELD_DEFINITION |
  ARGUMENT_DEFINITION | ENUM_VALUE | INPUT_FIELD_DEFINITION.`
  `Directive @auth at line 13, column 20 ... is missing its required argument 'role'.` Two
  `Directive @cached at line ... isn't repeatable but is applied more than once`, at line 14, column 32 (the second
  `@cached` on `members`) and line 9, column 15 (`extend schema`).

## Gotchas

- `@connection` must be declared in the SDL like any applied directive. Without a definition, the build throws
  `SchemaError: Found applied directive connection without corresponding directive definition.` unless
  `allowUndeclaredDirectives(true)` is set.
- The uppercased `ADA` and `GRACE` names also prove the wiring reached a nested type's field. Lowercase names there mean
  the wiring only ran on `Query`.
- `@since(2020-01-01)` proves `onScalar` reads an argument typed by the scalar the directive is applied to. `@since(null)`
  means the wiring saw a type reference.
- Cursors are base64 of `simple-cursor<index>` from graphql-java's `SimpleListConnection`. Don't hard-code them across
  graphql-java versions.
- Before #895 (commit 21f3796e) `wiring-shapes` has the same `schema.graphqls`, except that `TreeFilter.children` reads
  `"sees GraphQLTypeReference TreeFilter"`.
- Before #885 (commit 94a98a01) `directives-invalid` has an empty `probe.log` and the same `01-query.json`.
