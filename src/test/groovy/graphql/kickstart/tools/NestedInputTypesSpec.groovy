package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLSchema
import spock.lang.Specification

class NestedInputTypesSpec extends Specification {

    def "nested input types are parsed"() {
        when:
        GraphQLSchema schema = SchemaParser.newParser().schemaString('''\
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
                    ''').resolvers(new QueryResolver())
                .build()
                .makeExecutableSchema()
        GraphQL gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(new AsyncExecutionStrategy())
                .build()
        def data = Utils.assertNoGraphQlErrors(gql, [filter: [title: "title", requestFilter: [discountTypeFilter: [name: "discount"]]]]) {
            '''
                query materials($filter: MaterialFilter!) {
                    materials(filter: $filter) {
                        id
                    }
                }
                '''
        }

        then:
        noExceptionThrown()
        data.materials == []
    }

    def "nested input in extensions are parsed"() {
        when:
        GraphQLSchema schema = SchemaParser.newParser().schemaString('''\
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
                    ''').resolvers(new QueryResolver())
                .build()
                .makeExecutableSchema()
        GraphQL gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(new AsyncExecutionStrategy())
                .build()
        def data = Utils.assertNoGraphQlErrors(gql, [filter: [title: "title", requestFilter: [discountTypeFilter: [name: "discount"]]]]) {
            '''
                query materials($filter: MaterialFilter!) {
                    materials(filter: $filter) {
                        id
                    }
                }
                '''
        }

        then:
        noExceptionThrown()
        data.materials == []
    }

    class QueryResolver implements GraphQLQueryResolver {
        List<Material> materials(MaterialFilter filter) { Collections.emptyList() }
    }

    class Material {
        Long id
    }

    static class MaterialFilter {
        String title
        RequestFilter requestFilter
    }

    static class RequestFilter {
        List<RequestFilter> and
        List<RequestFilter> or
        DiscountTypeFilter discountTypeFilter
    }

    static class DiscountTypeFilter {
        String name
    }
}
