# Schema building

A user points `SchemaParser.newParser()` at SDL files on the classpath and hands it resolver objects. graphql-java-tools
matches every SDL type to a class and every field to a resolver method, getter or property, and returns an executable
`GraphQLSchema`. When it can't find a field's implementation, it fails the build with a message that lists every
signature it tried. Types no root can reach get a warning and are dropped from the schema.

## Sub-features

- `build-files` loads SDL with `.file()` and `.files()` from the classpath, including `extend type`.
- `build-root-resolver` resolves `Query` fields through a `GraphQLQueryResolver`'s methods, with and without arguments.
- `build-nested-resolver` resolves a field through a `GraphQLResolver<T>` method that takes the parent object.
- `build-properties` resolves fields through Kotlin properties and an `isX()` getter for a `Boolean!` field.
- `build-scalar` serializes a custom `GraphQLScalarType` registered with `.scalars()`.
- `build-unused-type` warns about a type no root can reach and leaves it out of the schema.
- `build-missing-resolver` fails the build with `FieldResolverError` and lists the signatures it searched.
- `build-read-only` builds a schema without mutation and subscription through `parseSchemaObjects().toReadOnlySchema()`,
  with interfaces, unions and custom directives working as in the full schema.

## Expected results

- The `bookstore` books are `b1` "The Dispossessed" (a1, stock 3), `b2` "The Left Hand of Darkness" (a1,
  stock 0) and `b3` "Use of Weapons" (a2, stock 1), and authors `a1` "Ursula K. Le Guin" and `a2` "Iain M. Banks".

- **Build and list.** `run bookstore`. Exit `0`, and `summary.txt` has
  `schema build: OK`, `unused definitions: Review`, and `operation 01-books: errors=0`.
- **Root, nested, properties, scalar.** In `01-books.json`, `data.books` has 3 entries in id order. `b1` has
  `"published": "1974-05-01"` and `"inStock": true`, `b2` has `"inStock": false`, and every `author.name` matches the data
  above.
- **Argument and null result.** `02-book-by-id.json` (variables `{"id": "b3"}`) has
  `data.book = {"title": "Use of Weapons", "author": {"id": "a2", "name": "Iain M. Banks"}}` and `data.missing = null`,
  with no `errors`.
- **Unused type.** `probe.log` contains `Schema type was defined but can never be accessed, and can be safely deleted:
  Review`. `schema.graphqls` has no `type Review`.
- **Missing resolver.** `run bookstore-missing-resolver`. Exit `2`. `summary.txt`
  has `schema build: FAILED graphql.kickstart.tools.resolver.FieldResolverError: No method or field found as defined in
  schema scenarios/bookstore-missing-resolver/extension.graphqls:2`. The 8 listed signatures start with
  `scenarios.AuthorResolver.isbn(scenarios.Book)`, have `scenarios.Book.isbn` sixth, and end with
  `scenarios.AuthorResolver.getIsbn(<environment or context>)`. `build-error.txt` has the stack trace.
- **Read-only schema.** `run read-only-schema`. Exit `0` and all three operations
  have `errors=0`. `01-interface.json` has `data.animal = {"__typename": "Cat", "name": "Tom", "lives": 9}`,
  `02-union.json` lists `Dog` Rex then `Cat` Tom, and `03-no-mutation.json` has `mutationType: null`. `schema.graphqls`
  has `directive @tag` and `@tag(name : "public")` on the three `name` fields. Before the #896 fix the build failed with
  `AssertException: There must be a type resolver for interface Animal`.

## Gotchas

- `bookstore-missing-resolver` loads `bookstore/schema.graphqls` too, so a change that breaks `bookstore` shows up here
  first as a different build error. Run `bookstore` first.
- The signature list in the error is in search priority order. A change to resolver lookup order shows up there before
  anything fails.
- `schema.graphqls` also lists graphql-java's built-in directives (`@defer`, `@oneOf`, ...). Their presence depends on
  the graphql-java version, not on graphql-java-tools.
