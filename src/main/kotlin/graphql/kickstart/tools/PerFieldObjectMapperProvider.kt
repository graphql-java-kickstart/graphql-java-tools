package graphql.kickstart.tools

import graphql.language.FieldDefinition
import tools.jackson.databind.ObjectMapper

/**
 * Supplies the ObjectMapper that converts the arguments of a resolver method, and that checks its input classes when the schema
 * is built. Called once per method-backed field, at schema build. The mapper is used as it is: its own features decide how
 * arguments convert, except FAIL_ON_EMPTY_BEANS, which the argument conversion always turns on for itself.
 */
fun interface PerFieldObjectMapperProvider {

    fun provide(fieldDefinition: FieldDefinition): ObjectMapper
}
