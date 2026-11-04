# Type extensions

`extend type`, `extend interface`, `extend union`, `extend enum`, `extend input` and `extend scalar` add to the type they
extend: fields, interfaces, members, values and directives are merged into one graphql-java type, base first. Where a type
declares an enum value, interface field or union member twice, or an extension applies a non-repeatable directive again,
the first one is kept and a WARN is logged. An object or input field declared twice keeps the last declaration, the type's
own for an input since its extensions are applied first, and logs a WARN that names that declaration. An extension of a different kind than its type is ignored with a WARN. A union,
enum, interface or scalar that only has extensions is built from them.

## Sub-features

- `ext-interface-fields` merges the fields of `extend interface`, keeping the base `id` and its description.
- `ext-implements` makes `extend type Video implements Node` take effect.
- `ext-union-members` merges the members of `extend union`.
- `ext-enum-values` merges the values of `extend enum`, keeping the base description of a repeated value.
- `ext-lone-enum` builds an enum that only has extensions, from all of them.
- `ext-directives` applies the directives of every extension after those of the type.
- `ext-non-repeatable` keeps the first of a non-repeatable directive that an extension applies again, with a WARN.
- `ext-repeats` warns about a repeated interface field, union member and enum value.
- `ext-repeated-fields` warns about an object or input field declared twice, between a type and its extension, between
  two extensions and inside one definition, and keeps the last declaration, the type's own for an input.
- `ext-other-kind` ignores `extend union Note` of `type Note` with a WARN.
- `ext-sealed` finds the class of a union member added by `extend union`, and of an implementor added by
  `extend type X implements Y`, among the subclasses of a sealed class or interface.

## Expected results

- **Run.** `run type-extensions`. Exit `0`, and `errors=0` for all three
  operations.
- **Fields and implements.** `01-node.json` has `article` with `createdAt = "2026-10-01"` and `title = "We launched"`, and
  `video` with `__typename = "Video"` and `seconds = 90`.
- **Members, values, lone enum.** `02-search-and-enums.json` has `search` with an `Article` and a `Video`,
  `colors = ["RED", "BLUE", "GREEN"]` and `shades = ["LIGHT", "DARK"]`.
- **Shape.** In `03-introspection.json`, `Node` has fields `id` ("The base id"), `createdAt`, `name`. `Video` has
  interfaces `["Node"]`, `SearchResult` has possible types `["Article", "Video"]`, and `Color.RED` has the description
  "The first red".
- **Directives.** `schema.graphqls` has `@tag(name : "base") @tag(name : "extension")` on `Node`, `SearchResult`, `Color`,
  `Filter` and `Article`, and `Article` has `@owner(team : "content")` once, before its `@tag`s.
- **Warnings.** `probe.log` has one WARN each for `Type 'Note' is defined as an object type, but extended as a union`,
  `@owner isn't repeatable`, `Type 'Node' declares field 'id'`, `Type 'SearchResult' declares member 'Article'` and
  `Type 'Color' declares value 'RED'`.
- **Repeated fields.** `run type-extensions-repeated-fields`. Exit `0`, `errors=0` for both operations, and the same
  schema, operation results and `inspect.txt` as master. `01-kept-declarations.json` has `typeAndExtension.name = "a"`,
  `twoExtensions = {"id": "b", "name": "c"}`, `oneDefinition.name = "d"` and `echo = "1,t,2,3"`. `inspect.txt` has
  `TypeAndExtension.name: From the extension`, `TwoExtensions.name: From the second extension`,
  `OneDefinition.name: The second`, `InputTypeAndExtension.id: From the type`,
  `InputTwoExtensions.id: From the second extension` and `InputOneDefinition.id: The second`. `probe.log` has exactly six
  WARNs, one per type, each `Type '<name>' declares field '<field>' more than once, only the one at line <n>, column 5 in
  scenarios/type-extensions-repeated-fields/schema.graphqls is kept.` with lines 18 (`TypeAndExtension`), 32
  (`TwoExtensions`), 39 (`OneDefinition`), 44 (`InputTypeAndExtension`), 63 (`InputTwoExtensions`) and 70
  (`InputOneDefinition`). On master `probe.log` is empty.
- **Sealed.** `run type-extensions-sealed`. `01-pets-and-animals.json` has
  `pets = [{"__typename": "Dog", "name": "Rex"}, {"__typename": "Cat", "lives": 9}]` and `animals` with a `Cow` and a
  `Horse`, with no WARN in `probe.log`.

## Gotchas

- Before extensions were merged (master before PR #855), `type-extensions` still builds but `01` and `02` return errors:
  `Node` loses `createdAt`, `Video` isn't a `Node`, and `BLUE` and `LIGHT` fail to serialize. `type-extensions-sealed`
  fails validation with `Unknown type 'Dog'`. Use that as the contrast run, not as a regression in this map.
- graphql-java allows only one `__type` per introspection query, so `03` reads `__schema` instead.
- The `Note` type is unreachable on purpose, so the "can be safely deleted" WARN for it is expected.
