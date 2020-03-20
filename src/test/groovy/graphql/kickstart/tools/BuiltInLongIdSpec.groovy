package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLSchema
import spock.lang.Shared
import spock.lang.Specification

class BuiltInLongIdSpec extends Specification {

    @Shared
    GraphQL gql

    def setupSpec() {
        GraphQLSchema schema = SchemaParser.newParser().schemaString('''\
                        type Query {
                            itemByLongId(id: ID!): Item!
                            itemsByLongIds(ids: [ID!]!): [Item!]!
                        }
                        
                        type Item {
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
                    itemsByLongIds(id: [1,2,3]) {
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

    class QueryWithLongItemResolver implements GraphQLQueryResolver {
        Item itemByLongId(Long id) {
            new Item(id: id)
        }

        List<Item> itemsByLongIds(List<Long> ids) {
            ids.collect { new Item(id: it) }
        }
    }

    class Item {
        Long id
    }
}
