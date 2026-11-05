package graphql.kickstart.tools.compat.scenarios

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.kickstart.tools.GraphQLResolver
import graphql.kickstart.tools.SchemaParser
import graphql.kickstart.tools.SchemaParserBuilder
import graphql.schema.GraphQLSchema

sealed interface Scenario {
    val name: String

    val operations: String get() = name

    val files: List<String>

    val resolvers: List<GraphQLResolver<*>>

    fun configure(builder: SchemaParserBuilder): SchemaParserBuilder = builder

    fun schema(parser: SchemaParser): GraphQLSchema = parser.makeExecutableSchema()

    fun inspect(schema: GraphQLSchema): String? = null

    fun graphQL(builder: GraphQL.Builder): GraphQL.Builder = builder

    fun input(builder: ExecutionInput.Builder): ExecutionInput.Builder = builder
}

fun Scenario.parser(resolvers: List<GraphQLResolver<*>> = this.resolvers): SchemaParserBuilder =
    configure(SchemaParser.newParser().files(*files.toTypedArray()).resolvers(resolvers))

val SCENARIOS: List<Scenario> = Scenario::class.sealedSubclasses.mapNotNull { it.objectInstance }.sortedBy { it.name }
