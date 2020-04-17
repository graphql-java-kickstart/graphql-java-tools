package graphql.kickstart.tools

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.language.FieldDefinition

interface PerFieldObjectMapperProvider {

    fun provide(fieldDefinition: FieldDefinition): ObjectMapper
}
