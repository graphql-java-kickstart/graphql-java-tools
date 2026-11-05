# Type extensions

`extend type`, `extend interface`, `extend union`, `extend enum`, `extend input` and `extend scalar` add to the type they
extend: fields, interfaces, members, values and directives are merged into one graphql-java type, base first. Where a type
and its extensions declare a field, enum value or union member twice, or apply a non-repeatable directive again, the build
fails with a `SchemaError`. For a field, value or member it names the type, the item and its line. For a directive it names
only the directive and its line, as in `Directive @once at line 1, column 25 isn't repeatable but is applied more than
once.` An extension of a different kind than its type is
ignored with a WARN. A union, enum, interface or scalar that only has extensions is built from them.

## Sub-features

- `ext-interface-fields` merges the fields of `extend interface`, keeping the base `id` and its description.
- `ext-implements` makes `extend type Video implements Node` take effect.
- `ext-union-members` merges the members of `extend union`.
- `ext-enum-values` merges the values of `extend enum`, keeping the base description.
- `ext-lone-enum` builds an enum that only has extensions, from all of them.
- `ext-directives` applies the directives of every extension after those of the type.
- `ext-repeats` fails the build on a field, enum value or union member declared twice, or a non-repeatable directive
  applied twice, across a type and its extensions. `type-extensions-repeated-fields` shows it for fields, and the
  `repeat` and `repeat-directive` cases of `CompatTest` cover every scenario type.
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
- **Warnings.** `probe.log` has `Type 'Note' is defined as an object type, but extended as a union. The extension is
  ignored.` and `Schema type was defined but can never be accessed, and can be safely deleted: Note`, and nothing else.
- **Repeated fields.** `run type-extensions-repeated-fields`. Exit `2`, and `summary.txt` has `schema build: FAILED
  graphql.kickstart.tools.SchemaError: Type 'InputTypeAndExtension' declares field 'id' more than once at line 49,
  column 5 in compat/scenarios/type-extensions-repeated-fields/schema.graphqls.`
- **Sealed.** `run type-extensions-sealed`. Exit `0`. `01-pets-and-animals.json` has
  `pets = [{"__typename": "Dog", "name": "Rex"}, {"__typename": "Cat", "lives": 9}]` and `animals` with a `Cow` and a
  `Horse`, with no WARN in `probe.log`.

## Gotchas

- Before extensions were merged (master before PR #855), `type-extensions` still builds but `01` and `02` return errors:
  `Node` loses `createdAt`, `Video` isn't a `Node`, and `BLUE` and `LIGHT` fail to serialize. `type-extensions-sealed`
  fails validation with `Unknown type 'Dog'`. Use that as the contrast run, not as a regression in this map.
- graphql-java allows only one `__type` per introspection query, so `03` reads `__schema` instead.
- The `Note` type is unreachable on purpose, so the "can be safely deleted" WARN for it is expected.
