package graphql.kickstart.tools;

import tools.jackson.databind.json.JsonMapper;

/**
 * Customizes the library's ObjectMapper for one resolver field. Jackson 3 mappers are immutable, so this gets the builder that
 * the mapper is built from. It already has the Kotlin module and the library's defaults, and a module added here replaces one
 * of the same type that was added before, e.g. a KotlinModule with other features.
 *
 * @author Andrew Potter
 */
public interface ObjectMapperConfigurer {

    void configure(JsonMapper.Builder builder, ObjectMapperConfigurerContext context);
}
