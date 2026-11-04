package scenarios

import graphql.Scalars
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.SchemaParser
import graphql.kickstart.tools.SchemaParserOptions
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import probe.dumpDirectives

object DirectiveDrift : Scenario {
    override val name = "directive-drift"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/directive-drift/schema.graphqls")
        .resolvers(DirectiveDriftQuery())
        .dictionary("Dog", DriftDog::class.java)
        .scalars(stringScalar("Date"), stringScalar("Money"))
        .options(SchemaParserOptions.newOptions().allowUndeclaredDirectives(true).build())

    override fun inspect(schema: GraphQLSchema) = dumpDirectives(schema)
}

object DirectiveDriftUndeclared : Scenario {
    override val name = "directive-drift-undeclared"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/directive-drift-undeclared/schema.graphqls")
        .resolvers(DirectiveDriftOneField())
}

private fun stringScalar(name: String) = GraphQLScalarType.newScalar().name(name).coercing(Scalars.GraphQLString.coercing).build()

interface DriftAnimal {
    val name: String
}

class DriftDog(override val name: String) : DriftAnimal

enum class DriftColor { RED, GREEN, BLUE }

data class DriftMeta(val owner: String?)

class DirectiveDriftQuery : GraphQLQueryResolver {
    fun plain() = "plain"
    fun wrongPlace(arg: String?) = arg
    fun missing() = "missing"
    fun old() = "old"
    fun older() = "older"
    fun search(filter: DriftMeta?, term: String?) = "${filter?.owner} $term"
    fun animal(): DriftAnimal = DriftDog("rex")
    fun pet(): Any = DriftDog("rex")
    fun color() = DriftColor.RED
    fun `when`() = "2026-10-22"
    fun price() = "9.99"
}

class DirectiveDriftOneField : GraphQLQueryResolver {
    fun a() = "a"
}
