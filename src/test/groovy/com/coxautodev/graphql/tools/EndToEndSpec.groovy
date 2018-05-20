package com.coxautodev.graphql.tools

import graphql.ExecutionResult
import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.execution.batched.BatchedExecutionStrategy
import graphql.schema.GraphQLSchema
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * @author Andrew Potter
 */
class EndToEndSpec extends Specification {

    @Shared
    GraphQL batchedGql

    @Shared
    GraphQL gql

    def setupSpec() {
        GraphQLSchema schema = EndToEndSpecKt.createSchema()

        batchedGql = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(new BatchedExecutionStrategy())
            .build()

        gql = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(new AsyncExecutionStrategy())
            .build()
    }

    def "schema comments are used as descriptions"() {
        expect:
            gql.graphQLSchema.allTypesAsList.find { it.name == 'Type' }?.valueDefinitionMap?.TYPE_1?.description == "Item type 1"
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

    def "generated schema should execute the subscription query"() {
        when:
            def newItem = new Item(1, "item", Type.TYPE_1, UUID.randomUUID(), [])
            def returnedItem = null
            def data = Utils.assertNoGraphQlErrors(gql, [:], new OnItemCreatedContext(newItem)) {
                '''
                subscription {
                    onItemCreated {
                        id
                    }
                } 
                '''
            }
            CountDownLatch latch = new CountDownLatch(1)
            (data as Publisher<ExecutionResult>).subscribe(new Subscriber<ExecutionResult>() {
                @Override
                void onSubscribe(org.reactivestreams.Subscription s) {

                }

                @Override
                void onNext(ExecutionResult executionResult) {
                    returnedItem = executionResult.data
                    latch.countDown()
                }

                @Override
                void onError(Throwable t) {
                }

                @Override
                void onComplete() {
                }
            })
            latch.await(3, TimeUnit.SECONDS)

        then:
            returnedItem.get("onItemCreated").id == 1
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

    def "generated schema should handle nested union types"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                    {
                        nestedUnionItems {
                            ... on Item {
                                itemId: id
                            }
                            ... on OtherItem {
                                otherItemId: id
                            }
                            ... on ThirdItem {
                                thirdItemId: id
                            }
                        }
                    }
                    '''
            }

        then:
            data.nestedUnionItems == [[itemId: 0], [itemId: 1], [otherItemId: 0], [otherItemId: 1], [thirdItemId: 100]]
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

    def "generated schema should handle optional arguments"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    missing: itemsWithOptionalInput {
                        id
                    }
                    
                    present: itemsWithOptionalInput(itemsInput: {name: "item1"}) {
                        id
                    }
                }
                '''
            }

        then:
            data.missing?.size > 1
            data.present?.size == 1
    }

    def "generated schema should handle optional arguments using java.util.Optional"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    missing: itemsWithOptionalInputExplicit {
                        id
                    }
                    
                    present: itemsWithOptionalInputExplicit(itemsInput: {name: "item1"}) {
                        id
                    }
                }
                '''
            }

        then:
            data.missing?.size > 1
            data.present?.size == 1
    }

    def "generated schema should handle optional return types using java.util.Optional"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    missing: optionalItem(itemsInput: {name: "item?"}) {
                        id
                    }
                    
                    present: optionalItem(itemsInput: {name: "item1"}) {
                        id
                    }
                }
                '''
            }

        then:
            data.missing == null
            data.present
    }

    def "generated schema should pass default arguments"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    defaultArgument
                }
                '''
            }

        then:
            data.defaultArgument == true
    }

    def "introspection shouldn't fail for arguments of type list with a default value (defaultEnumListArgument)"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                   __type(name: "Query") {
                       name
                       fields {
                         name
                         args {
                           name
                           defaultValue
                         }
                       }
                   }
                }
                '''
            }

        then:
            data.__type
    }

    def "generated schema should return null without errors for null value with nested fields"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    complexNullableType {
                        first
                        second
                        third
                    }
                }
                '''
            }

        then:
            data.containsKey('complexNullableType')
            data.complexNullableType == null
    }

    def "generated schema handles nested lists in input type fields"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    complexInputType(complexInput: [[{first: "foo", second: [[{first: "bar"}]]}]])
                }
                '''
            }

        then:
            data.complexInputType
    }

    def "generated schema should use type extensions"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    extendedType {
                        first
                        second
                    }
                }
                '''
            }

        then:
            data.extendedType
            data.extendedType.first
            data.extendedType.second
    }

    def "generated schema uses properties if no methods are found"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    propertyField
                }
                '''
            }

        then:
            data.propertyField
    }

    def "generated schema allows enums in input types"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    enumInputType(type: TYPE_2)
                }
                '''
            }

        then:
            data.enumInputType == "TYPE_2"
    }

    def "generated schema works with custom scalars as input values"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    customScalarMapInputType(customScalarMap: { test: "me" })
                }
                '''
            }

        then:
            data.customScalarMapInputType == [
                test: "me"
            ]
    }

    def "generated schema supports batched datafetchers"() {
        when:
            def data = Utils.assertNoGraphQlErrors(batchedGql) {
                '''
                {
                    allBaseItems {
                        name: batchedName
                    }
                }
                '''
            }

        then:
            data.allBaseItems.collect { it.name } == ['item1', 'item2']
    }

    def "generated schema supports batched datafetchers with params"() {
        when:
            def data = Utils.assertNoGraphQlErrors(batchedGql) {
                '''
                {
                    allBaseItems {
                        tags: batchedWithParamsTags(names: ["item2-tag1"]) {
                            name
                        }
                    }
                }
                '''
            }

        then:
            data.allBaseItems.collect { it.tags.collect { it.name } } == [[], ['item2-tag1']]
    }

    def "generated schema supports overriding built-in scalars"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    itemByBuiltInId(id: "38f685f1-b460-4a54-a17f-7fd69e8cf3f8") {
                        name
                    }
                }
                '''
            }

        then:
            noExceptionThrown()
            data.itemByBuiltInId != null
    }

    def "generated schema supports DataFetcherResult"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    dataFetcherResult {
                        name
                    }
                }
                '''
            }

        then:
            data.dataFetcherResult.name == "item1"
    }
}
