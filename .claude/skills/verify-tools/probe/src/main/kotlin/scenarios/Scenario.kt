package scenarios

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.kickstart.tools.SchemaParser
import graphql.kickstart.tools.SchemaParserBuilder
import graphql.schema.GraphQLSchema

/**
 * One way a user sets up graphql-java-tools: SDL and operations under `src/main/resources/scenarios/<name>/`, resolvers
 * and options in an `object` in this package, which registers it.
 */
sealed interface Scenario {
    val name: String

    /** The directory under `scenarios/` whose operations run against this scenario's schema. */
    val operations: String get() = name

    /** The configured builder. The probe calls `build()` on it and passes the result to [schema]. */
    fun parser(): SchemaParserBuilder

    fun schema(parser: SchemaParser): GraphQLSchema = parser.makeExecutableSchema()

    /** Extra evidence about the built schema, written to inspect.txt. */
    fun inspect(schema: GraphQLSchema): String? = null

    fun graphQL(builder: GraphQL.Builder): GraphQL.Builder = builder

    fun input(builder: ExecutionInput.Builder): ExecutionInput.Builder = builder
}

val SCENARIOS: List<Scenario> = Scenario::class.sealedSubclasses.mapNotNull { it.objectInstance }.sortedBy { it.name }
