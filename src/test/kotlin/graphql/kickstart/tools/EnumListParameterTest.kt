package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLSchema
import org.junit.Test

class EnumListParameterTest {
    private val schema: GraphQLSchema = SchemaParser.newParser().schemaString("""
            type Query {
                countries(regions: [Region!]!): [Country!]!
            }

            enum Region {
                EUROPE
                ASIA
            }

            type Country {
                code: String!
                name: String!
                regions: [Region!]
            }
        """.trimIndent())
            .resolvers(QueryResolver())
            .build()
            .makeExecutableSchema()
    private val gql: GraphQL = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()

    @Test
    fun `query with parameter type list of enums should resolve correctly`() {
        val data = assertNoGraphQlErrors(gql, mapOf("regions" to setOf("EUROPE", "ASIA"))) {
            """
                query getCountries(${'$'}regions: [Region!]!) {
                    countries(regions: ${'$'}regions){
                        code
                        name
                        regions
                    }
                }
            """
        }

        assert((data["countries"] as Collection<*>).isEmpty())
    }

    class QueryResolver : GraphQLQueryResolver {
        fun getCountries(regions: Set<Region>): Set<Country> {
            return setOf()
        }
    }

    class Country {
        var code: String? = null
        var name: String? = null
        var regions: List<Region>? = null
    }

    enum class Region {
        EUROPE,
        ASIA
    }
}
