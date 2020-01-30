package graphql.kickstart.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Andrew Potter
 */
public interface ObjectMapperConfigurer {
    void configure(ObjectMapper mapper, ObjectMapperConfigurerContext context);
}
