package graphql.kickstart.tools;

import graphql.language.Definition;

import java.util.List;

public interface TypeDefinitionFactory {

    /**
     * Called after parsing the SDL for creating any additional type definitions. All existing definitions are passed in. Return only
     * the newly created definitions.
     *
     * @param existing all existing definitions
     *
     * @return any new definitions that should be added
     */
    List<Definition<?>> create(final List<Definition<?>> existing);
}
