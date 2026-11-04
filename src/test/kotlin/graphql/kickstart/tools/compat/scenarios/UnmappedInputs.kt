package graphql.kickstart.tools.compat.scenarios

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import graphql.kickstart.tools.GraphQLQueryResolver
import java.util.Optional

object UnmappedInputs : Scenario {
    override val name = "unmapped-inputs"

    override val files = listOf("compat/scenarios/unmapped-inputs/schema.graphqls")
    override val resolvers = listOf(UnmappedInputsQuery())
}

class IgnoredInput {
    @field:JsonIgnoreProperties("legacy")
    var inner: Optional<InnerInput>? = null
}

class InnerInput {
    var name: String? = null
}

class TypoInput {
    var item: Optional<ItemInput>? = null
}

class ItemInput {
    var nmae: String? = null
}

class UnmappedInputsQuery : GraphQLQueryResolver {
    fun ignored(input: IgnoredInput): String? = input.inner?.get()?.name

    fun typo(input: TypoInput): String? = input.item?.get()?.nmae
}
