package graphql.kickstart.tools

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * @author Andrew Potter
 */
interface ObjectMapperConfigurer {
    fun configure(mapper: ObjectMapper?, context: ObjectMapperConfigurerContext?)
}
