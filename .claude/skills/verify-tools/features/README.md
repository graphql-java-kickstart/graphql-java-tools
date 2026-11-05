# graphql-java-tools verification map

Each file below is one user-visible feature, with the scenarios that prove it and their exact expected results. The
commands and proof standards are in `../SKILL.md`. Scenario data is fixed, so expected values are literal.

A feature file has an H1 title and one paragraph on the behavior a user sees, then three H2 sections: `Sub-features` (short
IDs, one line each), `Expected results` (each scenario's `run` command and what its evidence shows), and `Gotchas` (traps
that waste or invalidate a run, and how the result looked before the fix).

## Features

- [Schema building](./schema-building.md): SDL files, root and nested resolvers, property and getter discovery, custom
  scalars, the unused-type warning, the missing-resolver error and the read-only schema (`bookstore`,
  `bookstore-missing-resolver`, `read-only-schema`).
- [Arguments and input types](./arguments-and-inputs.md): SDL defaults, enums, lists, input objects mapped to classes,
  omitted versus null, parameters typed by a type variable, custom scalar arguments, recursive input objects and the WARN
  for unmapped input fields (`inputs`, `unmapped-inputs`, `generic-input-resolver`, `scalar-args`, `recursive-inputs`).
- [Async resolvers and subscriptions](./async-and-subscriptions.md): `CompletableFuture`, suspend functions, and
  `Publisher` subscriptions (`async`).
- [Generic wrappers](./generic-wrappers.md): wrappers and futures nested in lists and other wrappers, with and without a
  `ValueUnboxer`, and in suspend resolvers (`nested-wrappers`, `nested-wrappers-unboxer`, `nested-wrappers-suspend`).
- [DataFetcherResult inside an Optional](./optional-data-fetcher-results.md): data, errors, empty, a list, the same type
  returned another way, and the nested-result rejection (`optional-results`, `optional-results-mixed`,
  `optional-results-list`, `optional-results-nested`).
- [Interfaces and unions](./interfaces-and-unions.md): runtime type resolution through the dictionary, including a class
  whose name differs from the type, and a union that is only a member of another union (`polymorphism`,
  `nested-unions`).
- [Directives and relay connections](./directives-and-relay.md): `SchemaDirectiveWiring`, `@deprecated`, `@connection`
  type generation, directives and `@specifiedBy` on scalars, wirings on recursive and cross-referencing types, applied
  versus legacy directive lists, undeclared directives and invalid applications (`directives`, `scalar-directives`,
  `wiring-shapes`, `directive-drift`, `directive-drift-undeclared`, `directives-invalid`).
- [Schema parser options](./schema-options.md): `missingResolverDataFetcherProvider`
  (`options`).
- [Type extensions](./type-extensions.md): fields, interfaces, members, values and directives of every kind of extension,
  the repeat errors and the kind-mismatch warning, repeated object and input fields, and sealed subclasses found through
  extensions (`type-extensions`, `type-extensions-repeated-fields`, `type-extensions-sealed`).
- [Proxied resolvers](./proxies.md): Spring JDK dynamic and CGLIB proxies, and the build error for fields a JDK proxy can't
  call (`jdk-proxy`, `jdk-proxy-cglib`, `jdk-proxy-plain`).
- [Context and environment parameters](./context-and-environment.md): `contextClass`, `GraphQLContext` and
  `DataFetchingEnvironment` after the schema arguments, in any order, the misbound-argument error, and resolver methods
  without the source parameter (`context-and-environment`, `context-and-environment-invalid`, `context-misbound`,
  `sourceless`, `sourceless-shadowed`, `sourceless-unimplemented`, `sourceless-misbound`).

Not mapped yet: field visibility, per-field object mappers, the Guice, Javassist and Weld proxy handlers,
`includeUnusedTypes`, and comment descriptions. Add a scenario and a feature entry when a change touches one of them.
