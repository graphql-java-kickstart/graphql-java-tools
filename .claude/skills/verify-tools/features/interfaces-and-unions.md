# Interfaces and unions

A field can return an interface or a union while the resolver returns a supertype (`Entity`, `Any`). graphql-java-tools
can't learn the concrete classes from those return types, so the user registers them with `.dictionary(...)`. At runtime
the object's class decides the GraphQL type. When the class name differs from the type, the user registers it with an
explicit name: `.dictionary("Writer", WriterEntity::class.java)`.

## Sub-features

- `poly-interface` resolves `node(id): Node` to `Novel` or `Writer` from the returned class.
- `poly-union` resolves each element of `[SearchResult!]!` (`Novel | Writer`) from a `List<Any>`.
- `poly-dictionary-name` maps the type `Writer` to the class `WriterEntity` by an explicit dictionary name.
- `poly-dictionary-class` maps `Novel` to the class `Novel` by `.dictionary(Novel::class.java)`.
- `nested-union` builds `union Result = Book | Media` with `union Media = Movie` when no field returns `Media`, and flattens
  `Result` to `Book | Movie` (scenario `nested-unions`).

## Expected results

- The data is `Novel("n1", "Solaris")` and `WriterEntity("w1", "Stanisław Lem")`.

- **Run.** `run polymorphism`. Exit `0`, `errors=0` for both operations, and an
  empty `probe.log`. A dictionary warning there means an entry went unused.
- **Interface.** `01-node.json` has `data.novel = {"__typename": "Novel", "id": "n1", "title": "Solaris"}` and
  `data.writer = {"__typename": "Writer", "name": "Stanisław Lem"}`.
- **Union.** `02-union.json` has `data.search` equal to `[{"__typename": "Novel", "title": "Solaris"},
  {"__typename": "Writer", "name": "Stanisław Lem"}]`.
- **Schema.** `schema.graphqls` has `union SearchResult = Novel | Writer` and both types implementing `Node`.
- **Nested union.** `run nested-unions`. Exit `0`, `errors=0` and an empty
  `probe.log`. `01-results.json` has `data.results` equal to `[{"__typename": "Book", "title": "Solaris"},
  {"__typename": "Movie", "title": "Stalker"}]`, the `Movie` reached through `... on Media`. `schema.graphqls` has
  `union Media = Movie` and `union Result = Book | Movie`.

## Gotchas

- Without `.dictionary(Novel::class.java)`, the build fails (exit `2`) with `SchemaClassScannerError: Object type
  'Novel' implements a known interface, but no class could be found for that type name.` To prove a dictionary change,
  use a separate scenario, because this one must keep building.
- `nested-unions` fails to build (exit `2`) on checkouts without #887, with `No object type found with name 'Media' for
  union`.
- `__typename` is the assertion that matters. Fields selected through the wrong inline fragment are silently absent, not
  errors.
