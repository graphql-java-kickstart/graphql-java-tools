package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLSchema
import spock.lang.Shared
import spock.lang.Specification

class BuiltInIdSpec extends Specification {

    @Shared
    GraphQL gql

    def setupSpec() {
        GraphQLSchema schema = SchemaParser.newParser().schemaString('''\
                        type Query {
                            itemByLongId(id: ID!): Item1!
                            itemsByLongIds(ids: [ID!]!): [Item1!]!
                            itemByUuidId(id: ID!): Item2!
                            itemsByUuidIds(ids: [ID!]!): [Item2!]!
                        }
                        
                        type Item1 {
                          id: ID!
                        }
                        
                        type Item2 {
                          id: ID!
                        }
                    '''.stripIndent())
                .resolvers(new QueryWithLongItemResolver())
                .build()
                .makeExecutableSchema()
        gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(new AsyncExecutionStrategy())
                .build()
    }

    def "supports Long as ID as input"() {
        when:
        def data = Utils.assertNoGraphQlErrors(gql) {
            '''
                {
                    itemByLongId(id: 1) {
                        id
                    }
                }
                '''
        }

        then:
        data.itemByLongId != null
        data.itemByLongId.id == "1"
    }

    def "supports list of Long as ID as input"() {
        when:
        def data = Utils.assertNoGraphQlErrors(gql) {
            '''
                {
                    itemsByLongIds(ids: [1,2,3]) {
                        id
                    }
                }
                '''
        }

        then:
        data.itemsByLongIds != null
        data.itemsByLongIds.size == 3
        data.itemsByLongIds[0].id == "1"
    }

    def "supports UUID as ID as input"() {
        when:
        def data = Utils.assertNoGraphQlErrors(gql) {
            '''
                {
                    itemByUuidId(id: "00000000-0000-0000-0000-000000000000") {
                        id
                    }
                }
                '''
        }

        then:
        data.itemByUuidId != null
        data.itemByUuidId.id == "00000000-0000-0000-0000-000000000000"
    }

    def "supports list of UUID as ID as input"() {
        when:
        def data = Utils.assertNoGraphQlErrors(gql) {
            '''
                {
                    itemsByUuidIds(ids: ["00000000-0000-0000-0000-000000000000","11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222"]) {
                        id
                    }
                }
                '''
        }

        then:
        data.itemsByUuidIds != null
        data.itemsByUuidIds.size == 3
        data.itemsByUuidIds[0].id == "00000000-0000-0000-0000-000000000000"
    }

    class QueryWithLongItemResolver implements GraphQLQueryResolver {
        Item1 itemByLongId(Long id) {
            new Item1(id: id)
        }

        List<Item1> itemsByLongIds(List<Long> ids) {
            ids.collect { new Item1(id: it) }
        }

        Item2 itemByUuidId(UUID id) {
            new Item2(id: id)
        }

        List<Item2> itemsByUuidIds(List<UUID> ids) {
            ids.collect { new Item2(id: it) }
        }
    }

    class Item1 {
        Long id
    }

    class Item2 {
        UUID id
    }
}
