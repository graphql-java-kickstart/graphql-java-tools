package graphql.kickstart.tools.compat.scenarios

import com.fasterxml.jackson.annotation.JsonIdentityInfo
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.ObjectIdGenerators
import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParserBuilder
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.GraphQLScalarType
import java.util.Locale
import java.util.Optional

object ScalarArgs : Scenario {
    override val name = "scalar-args"

    override val files = listOf("compat/scenarios/scalar-args/schema.graphqls")
    override val resolvers = listOf(ScalarArgsQuery())

    override fun configure(builder: SchemaParserBuilder) = builder
        .scalars(tagScalar, tokenScalar, typedTokenScalar, wrappedTokenScalar, idTokenScalar)
}

class Tag private constructor(val value: String) {
    companion object {
        fun of(value: String) = Tag(value)
    }
}

private val tagScalar = GraphQLScalarType.newScalar()
    .name("Tag")
    .coercing(object : Coercing<Tag, String> {
        override fun serialize(dataFetcherResult: Any, context: GraphQLContext, locale: Locale) = (dataFetcherResult as Tag).value
        override fun parseValue(input: Any, context: GraphQLContext, locale: Locale) = Tag.of(input as String)
        override fun parseLiteral(input: Value<*>, variables: CoercedVariables, context: GraphQLContext, locale: Locale) =
            Tag.of((input as StringValue).value.orEmpty())
    })
    .build()

class ScalarArgsToken(private val value: String = "default") {
    override fun toString() = value
}

data class ScalarArgsTokenInput(val token: ScalarArgsToken? = null)

@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY)
class ScalarArgsTypedToken(private val value: String = "default") {
    override fun toString() = value
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
class ScalarArgsWrappedToken(private val value: String = "default") {
    override fun toString() = value
}

@JsonIdentityInfo(generator = ObjectIdGenerators.IntSequenceGenerator::class)
class ScalarArgsIdToken(private val value: String = "default") {
    override fun toString() = value
}

data class ScalarArgsMarkedTokensInput(
    val typed: ScalarArgsTypedToken? = null,
    val wrapped: ScalarArgsWrappedToken? = null,
    val ids: List<ScalarArgsIdToken>? = null
)

private val tokenScalar = stringScalar("Token") { ScalarArgsToken(it) }
private val typedTokenScalar = stringScalar("TypedToken") { ScalarArgsTypedToken(it) }
private val wrappedTokenScalar = stringScalar("WrappedToken") { ScalarArgsWrappedToken(it) }
private val idTokenScalar = stringScalar("IdToken") { ScalarArgsIdToken(it) }

private fun <T : Any> stringScalar(name: String, parse: (String) -> T) = GraphQLScalarType.newScalar()
    .name(name)
    .coercing(object : Coercing<T, String> {
        override fun serialize(dataFetcherResult: Any, context: GraphQLContext, locale: Locale) = dataFetcherResult.toString()
        override fun parseValue(input: Any, context: GraphQLContext, locale: Locale) = parse(input as String)
        override fun parseLiteral(input: Value<*>, variables: CoercedVariables, context: GraphQLContext, locale: Locale) =
            parse((input as StringValue).value.orEmpty())
    })
    .build()

class ScalarArgsQuery : GraphQLQueryResolver {
    fun tag(tag: Tag) = "tag ${tag.value}"
    fun tags(tags: List<Tag>) = "tags ${tags.map { it.value }}"
    fun id(id: Long) = "id ${id + 1} (${id.javaClass.name})"
    fun nestedSets(tags: List<Set<String>>) = "sets ${tags.map { it.sorted() }} sizes ${tags.map { it.size }}"
    fun nestedLists(tags: List<List<String>>) = "lists $tags"
    fun tokenInput(input: ScalarArgsTokenInput) = "token ${input.token}"
    fun markedTokens(input: ScalarArgsMarkedTokensInput) = "tokens ${input.typed} ${input.wrapped} ${input.ids}"
    fun optionalTypedToken(token: Optional<ScalarArgsTypedToken>) = "typed ${token.orElse(null)}"
    fun optionalIdToken(token: Optional<ScalarArgsIdToken>) = "id ${token.orElse(null)}"
}
