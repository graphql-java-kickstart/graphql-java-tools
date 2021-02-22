package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import org.junit.Test

class NestedInputTypesTest {

    @Test
    fun `nested input types are parsed`() {
        val schema = SchemaParser.newParser().schemaString("""
                    type Query {
                        materials(filter: MaterialFilter): [Material!]!
                    }
                    
                    input MaterialFilter {
                        title: String
                        requestFilter: RequestFilter
                    }
                    
                    input RequestFilter {
                        and: [RequestFilter!]
                        or: [RequestFilter!]
                        discountTypeFilter: DiscountTypeFilter
                    }
                    
                    input DiscountTypeFilter {
                        name: String
                    }
                    
                    type Material {
                        id: ID!
                    }
                """)
                .resolvers(QueryResolver())
                .build()
                .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(AsyncExecutionStrategy())
                .build()
        val data = assertNoGraphQlErrors(gql, mapOf("filter" to mapOf("title" to "title", "requestFilter" to mapOf("discountTypeFilter" to mapOf("name" to "discount"))))) {
            """
            query materials(${'$'}filter: MaterialFilter!) {
                materials(filter: ${'$'}filter) {
                    id
                }
            }
            """
        }

        assert((data["materials"] as Collection<*>).isEmpty())
    }

    @Test
    fun `nested input in extensions are parsed`() {
        val schema = SchemaParser.newParser().schemaString("""
                    type Query {
                        materials(filter: MaterialFilter): [Material!]!
                    }
                    
                    input MaterialFilter {
                        title: String
                    }
                    
                    extend input MaterialFilter {
                        requestFilter: RequestFilter
                    }
                    
                    input RequestFilter {
                        and: [RequestFilter!]
                        or: [RequestFilter!]
                        discountTypeFilter: DiscountTypeFilter
                    }
                    
                    input DiscountTypeFilter {
                        name: String
                    }
                    
                    type Material {
                        id: ID!
                    }
                """)
                .resolvers(QueryResolver())
                .build()
                .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(AsyncExecutionStrategy())
                .build()
        val data = assertNoGraphQlErrors(gql, mapOf("filter" to mapOf("title" to "title", "requestFilter" to mapOf("discountTypeFilter" to mapOf("name" to "discount"))))) {
            """
                query materials(${'$'}filter: MaterialFilter!) {
                    materials(filter: ${'$'}filter) {
                       id
                    }
                }
            """
        }

        assert((data["materials"] as Collection<*>).isEmpty())
    }

    class QueryResolver : GraphQLQueryResolver {
        fun materials(filter: MaterialFilter): List<Material> = listOf()
    }

    class Material {
        var id: Long? = null
    }

    class MaterialFilter {
        var title: String? = null
        var requestFilter: RequestFilter? = null
    }

    class RequestFilter {
        var and: List<RequestFilter>? = null
        var or: List<RequestFilter>? = null
        var discountTypeFilter: DiscountTypeFilter? = null
    }

    class DiscountTypeFilter {
        var name: String? = null
    }

}
