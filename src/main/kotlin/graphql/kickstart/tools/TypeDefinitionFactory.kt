package graphql.kickstart.tools

import graphql.language.Definition

interface TypeDefinitionFactory {

    /**
     * Called after parsing the SDL for creating any additional type definitions. All existing definitions are passed in. Return only
     * the newly created definitions.
     *
     * @param existing all existing definitions
     *
     * @return any new definitions that should be added
     */
    fun create(existing: List<Definition<*>?>?): List<Definition<*>?>?
}
