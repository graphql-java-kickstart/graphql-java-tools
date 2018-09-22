package com.coxautodev.graphql.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLSchema
import spock.lang.Shared
import spock.lang.Specification

class EnumListParameterSpec extends Specification {

    @Shared
    GraphQL gql

    def setupSpec() {
        GraphQLSchema schema = SchemaParser.newParser().schemaString('''\
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
                    '''.stripIndent())
                .resolvers(new QueryResolver())
                .build()
                .makeExecutableSchema()
        gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(new AsyncExecutionStrategy())
                .build()
    }

    def "query with parameter type list of enums should resolve correctly"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql, [regions: ["EUROPE", "ASIA"]]) {
                '''
                query getCountries($regions: [Region!]!) {
                  countries(regions: $regions){
                    code
                    name
                    regions
                  }
                }
                '''
            }

        then:
            data.countries == []
    }

    class QueryResolver implements GraphQLQueryResolver {
        Set<Country> getCountries(Set<Region> regions) {
            return Collections.emptySet()
        }
    }

    class Country {
        String code
        String name
        List<Region> regions
    }

    enum Region {
        EUROPE,
        ASIA
    }

}


