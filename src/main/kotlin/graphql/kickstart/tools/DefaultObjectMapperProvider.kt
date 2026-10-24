package graphql.kickstart.tools

import graphql.language.FieldDefinition
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

internal class DefaultObjectMapperProvider(private val configurer: ObjectMapperConfigurer? = null) : PerFieldObjectMapperProvider {

    override fun provide(fieldDefinition: FieldDefinition): ObjectMapper =
        builder().also { configurer?.configure(it, ObjectMapperConfigurerContext(fieldDefinition)) }.build()

    private fun builder(): JsonMapper.Builder = jacksonMapperBuilder()
        // an input field the class has no property for fails the request rather than losing the client's value
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
}
