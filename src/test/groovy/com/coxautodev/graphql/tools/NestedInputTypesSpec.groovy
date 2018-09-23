package com.coxautodev.graphql.tools

import spock.lang.Specification

class NestedInputTypesSpec extends Specification {

    def "nested input types are parsed"() {
        when:
            SchemaParser.newParser().schemaString('''\
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

        then:
            noExceptionThrown()
    }

    class QueryResolver implements GraphQLQueryResolver {
        List<Material> materials(MaterialFilter filter) { Collections.emptyList() }
    }

    class Material {
        Long id
    }

    class MaterialFilter {
        String title
        RequestFilter requestFilter
    }

    class RequestFilter {
        List<RequestFilter> and
        List<RequestFilter> or
        DiscountTypeFilter discountTypeFilter
    }

    class DiscountTypeFilter {
        String name
    }
}
