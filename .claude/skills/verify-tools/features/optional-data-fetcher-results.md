# DataFetcherResult inside an Optional

A resolver can return `Optional<DataFetcherResult<T>>` for a nullable field. graphql-java gets the `DataFetcherResult`, so
the field resolves its data and the query keeps its errors. An empty `Optional` resolves to null. A `DataFetcherResult` or an
`Optional` twice in one value fails the build.

## Sub-features

- `opt-result` resolves `Optional<DataFetcherResult<Payload>>` for `Payload` with its error, and
  `Optional<DataFetcherResult<String>>` for `String`.
- `opt-result-empty` resolves `Optional.empty()` to null.
- `opt-result-mixed` builds when the same object type is also returned as a `DataFetcherResult<Optional<Payload>>`.
- `opt-result-list` resolves `Optional<DataFetcherResult<List<Payload>>>` for `[Payload]`.
- `opt-result-nested` rejects `Optional<DataFetcherResult<DataFetcherResult<Payload>>>`.

## Expected results

- **Run.** `run optional-results`. Exit `0` and an empty `probe.log`. `01-optional-result.json` has
  `data.optionalResult = {"data": "a"}` and one error, `partial`. `02-optional-result-name.json` has
  `data.optionalResultName = "a"` and `03-empty-optional-result.json` has `data.emptyOptionalResult = null`, both without
  errors.
- **Mixed.** `run optional-results-mixed`. Exit `0`. `01-optional-result.json` matches the one above and
  `02-result-of-optional.json` has `data.resultOfOptional = {"data": "a"}`.
- **List.** `run optional-results-list`. Exit `0`. `01-optional-result-list.json` has
  `data.optionalResultList = [{"data": "a"}, {"data": "b"}]`.
- **Nested.** `run optional-results-nested`. Exit `2`. `build-error.txt` ends in
  `graphql.execution.DataFetcherResult cannot be nested within itself`.

## Gotchas

- Before the fix (master 3f022b91) `optional-results` still exits `0`. `01` has
  `data.optionalResult = {"data": "scenarios.OptionalResultsPayload@<hash>"}` with no error, and `02` has
  `"DataFetcherResult{data=a, errors=[], localContext=null, extensions=null}"`. `optional-results-mixed` exits `2` with
  `Two different classes used for type Payload`, `optional-results-list` exits `2` with `Java class is not a List or generic
  type information was lost`, and `optional-results-nested` exits `0` with the same `Payload@<hash>` string.
