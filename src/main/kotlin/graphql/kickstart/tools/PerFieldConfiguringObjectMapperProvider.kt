package graphql.kickstart.tools

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import graphql.language.FieldDefinition

class PerFieldConfiguringObjectMapperProvider(
    private val objectMapperConfigurer: ObjectMapperConfigurer = ObjectMapperConfigurer { _, _ -> }
) : PerFieldObjectMapperProvider {

    override fun provide(fieldDefinition: FieldDefinition): ObjectMapper {
        return ObjectMapper().apply {
            objectMapperConfigurer.configure(this, ObjectMapperConfigurerContext(fieldDefinition))
        }
			.registerModule(Jdk8Module())
			.registerModule(JavaTimeModule())
			.registerKotlinModule()
    }
}
