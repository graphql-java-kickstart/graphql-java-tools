package graphql.kickstart.tools

/**
 * What the [SchemaParser] does about input object fields that the class of a resolver argument has no property for,
 * see [SchemaParserOptions.Builder.unmappedInputFields].
 */
enum class UnmappedInputFields {
    /** Doesn't check the classes. */
    IGNORE,

    /** Logs a warning for each field. */
    WARN,

    /** Fails with a [SchemaError] that lists every field. */
    FAIL
}
