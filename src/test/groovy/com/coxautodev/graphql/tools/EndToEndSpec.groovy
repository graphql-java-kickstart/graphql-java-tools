package com.coxautodev.graphql.tools

import graphql.GraphQL
import spock.lang.Shared
import spock.lang.Specification

/**
 * @author Andrew Potter
 */
class EndToEndSpec extends Specification {

    @Shared
    GraphQL gql

    def setupSpec() {
        gql = new GraphQL(SchemaParser.newParser()
            .schemaString(EndToEndSpecKt.schemaDefinition)
            .resolvers(new Query(), new Mutation(), new ItemResolver())
            .scalars(EndToEndSpecKt.CustomUUIDScalar)
            .enums(Type)
            .dataClasses(Tag, OtherItem)
            .build()
            .parseSchemaObjects()
            .toSchema())
    }

    def "generated schema should respond to simple queries"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    items(itemsInput: {name: "item1"}) {
                        id
                        type
                    }
                }
                '''
            }

        then:
            noExceptionThrown()
    }

    def "generated schema should respond to simple mutations"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql, [name: "new1", type: Type.TYPE_2.toString()]) {
                '''
                mutation addNewItem($name: String!, $type: Type!) {
                    addItem(newItem: {name: $name, type: $type}) {
                        id
                        name
                        type
                    }
                }
                '''
            }

        then:
            data.addItem
    }

    def "generated schema should handle interface types"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    itemsByInterface {
                        name
                        type
                    }
                }
                '''
            }

        then:
            data.itemsByInterface
    }

    def "generated schema should handle union types"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    allItems {
                        ... on Item {
                            id
                            name
                        }
                        ... on OtherItem {
                            name
                            type
                        }
                    }
                }
                '''
            }

        then:
            data.allItems
    }

    def "generated schema should handle scalar types"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                    {
                        itemByUUID(uuid: "38f685f1-b460-4a54-a17f-7fd69e8cf3f8") {
                            uuid
                        }
                    }
                    '''
            }

        then:
            data.itemByUUID
    }
}
