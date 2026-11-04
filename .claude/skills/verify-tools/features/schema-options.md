# Schema parser options

`SchemaParserOptions` changes how the schema is built and what clients can see. A `missingResolverDataFetcherProvider`
builds a data fetcher for fields that have no resolver, where the build would otherwise fail.

## Sub-features

- `opt-missing-resolver-provider` serves a field that has no resolver method or property from the provider's data fetcher.

## Expected results

- **Run.** `run options`. Exit `0`. `summary.txt` has `01-fields: errors=0`.
- **Fields and typename.** `01-fields.json` has `data = {"name": "tools", "unimplemented": "no resolver for
  unimplemented", "__typename": "Query"}`.

## Gotchas

- The provider doesn't log `Missing resolver for field`. Only `allowUnimplementedResolvers(true)` does, and it also logs
  a development-only warning. Don't expect either in `probe.log` for this scenario.
- `missingResolverDataFetcher(...)` is the deprecated predecessor of the provider. Prove changes to it in a separate
  scenario so this one stays free of deprecated API.
