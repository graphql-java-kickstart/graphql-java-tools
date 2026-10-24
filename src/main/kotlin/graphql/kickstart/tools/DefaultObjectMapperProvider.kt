package graphql.kickstart.tools

import graphql.language.FieldDefinition
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

internal class DefaultObjectMapperProvider(private val configurer: ObjectMapperConfigurer? = null) : PerFieldObjectMapperProvider {

    private val shared by lazy { builder().build() }

    override fun provide(fieldDefinition: FieldDefinition): ObjectMapper =
        if (configurer == null) shared
        else builder().also { configurer.configure(it, ObjectMapperConfigurerContext(fieldDefinition)) }.build()

    private fun builder(): JsonMapper.Builder = jacksonMapperBuilder()
        // an input field the class has no property for fails the request rather than losing the client's value
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        // GraphQL enum values are the Java constants' names, so an enum converts to and from a String by name()
        .disable(EnumFeature.READ_ENUMS_USING_TO_STRING, EnumFeature.WRITE_ENUMS_USING_TO_STRING)
}
