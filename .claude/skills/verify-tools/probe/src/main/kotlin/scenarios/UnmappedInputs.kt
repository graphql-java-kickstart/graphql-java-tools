package scenarios

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser
import java.util.Optional

/** Input classes with an Optional property: one ignores a field on purpose, the other misspells one. */
object UnmappedInputs : Scenario {
    override val name = "unmapped-inputs"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/unmapped-inputs/schema.graphqls")
        .resolvers(UnmappedInputsQuery())
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
