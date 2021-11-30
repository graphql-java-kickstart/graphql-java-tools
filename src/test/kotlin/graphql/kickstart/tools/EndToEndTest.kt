package graphql.kickstart.tools

import com.fasterxml.jackson.module.kotlin.jacksonMapperBuilder
import graphql.*
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.*
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import org.junit.Test
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.reactivestreams.tck.TestEnvironment
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EndToEndTest {

    private val schema: GraphQLSchema = createSchema()
    private val gql: GraphQL = GraphQL.newGraphQL(schema)
        .queryExecutionStrategy(AsyncExecutionStrategy())
        .build()

    @Test
    fun `schema comments are used as descriptions`() {
        val type = schema.allTypesAsList.find { it.name == "Type" } as GraphQLEnumType
        assert(type.values[0].description == "Item type 1")
        assert(type.values[1].description == "Item type 2")
    }

    @Test
    fun `generated schema should respond to simple queries`() {
        assertNoGraphQlErrors(gql) {
            """
            {
                items(itemsInput: {name: "item1"}) {
                    id
                    type
                }
            }
            """
        }
    }

    @Test
    fun `generated schema should respond to simple mutations`() {
        val data = assertNoGraphQlErrors(gql, mapOf("name" to "new1", "type" to Type.TYPE_2.toString())) {
            """
            mutation addNewItem(${'$'}name: String!, ${'$'}type: Type!) {
                addItem(newItem: {name: ${'$'}name, type: ${'$'}type}) {
                    id
                    name
                    type
                }
            }
            """
        }

        assertNotNull(data["addItem"])
    }

    @Test
    fun `generated schema should execute the subscription query`() {
        val newItem = Item(1, "item", Type.TYPE_1, UUID.randomUUID(), listOf())
        var returnedItem: Map<String, Map<String, Any>>? = null

        val closure = {
            """
            subscription {
                onItemCreated {
                    id
                }
            }
            """
        }

        val result = gql.execute(ExecutionInput.newExecutionInput()
            .query(closure.invoke())
            .graphQLContext(mapOf("newItem" to newItem))
            .variables(mapOf()))

        val data = result.getData() as Publisher<ExecutionResult>
        val latch = CountDownLatch(1)
        data.subscribe(object : Subscriber<ExecutionResult> {
            override fun onNext(item: ExecutionResult?) {
                returnedItem = item?.getData()
                latch.countDown()
            }

            override fun onError(throwable: Throwable?) {}
            override fun onComplete() {}
            override fun onSubscribe(p0: Subscription?) {}
        })
        latch.await(3, TimeUnit.SECONDS)

        assert(result.errors.isEmpty())
        assertEquals(returnedItem?.get("onItemCreated"), mapOf("id" to 1))
    }

    @Test
    fun `generated schema should handle interface types`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                itemsByInterface {
                    name
                    type
                }
            }
            """
        }

        assertNotNull(data["itemsByInterface"])
    }

    @Test
    fun `generated schema should handle union types`() {
        val data = assertNoGraphQlErrors(gql) {
            """
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
            """
        }

        assertNotNull(data["allItems"])
    }

    @Test
    fun `generated schema should handle nested union types`() {
        val data = assertNoGraphQlErrors(gql) {
            """
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
            """
        }

        assertEquals(data["nestedUnionItems"], listOf(
            mapOf("itemId" to 0),
            mapOf("itemId" to 1),
            mapOf("otherItemId" to 0),
            mapOf("otherItemId" to 1),
            mapOf("thirdItemId" to 100)
        ))
    }

    @Test
    fun `generated schema should handle scalar types`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                itemByUUID(uuid: "38f685f1-b460-4a54-a17f-7fd69e8cf3f8") {
                    uuid
                }
            }
            """
        }

        assertNotNull(data["itemByUUID"])
    }

    @Test
    fun `generated schema should handle union types with deep hierarchy`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                findSuitableDog(preferredColor: "chocolate", minimumFluffiness: 31) {
                    ... on Dog { name }
                    ... on NoDogError { msg }
                }
            }
            """
        }

        assertNotNull(data["findSuitableDog"])
    }

    @Test
    fun `generated schema should handle non nullable scalar types`() {
        val fileParts = listOf(MockPart("test.doc", "Hello"), MockPart("test.doc", "World"))
        val args = mapOf("fileParts" to fileParts)
        val data = assertNoGraphQlErrors(gql, args) {
            """
            mutation (${'$'}fileParts: [Upload!]!) { echoFiles(fileParts: ${'$'}fileParts) }
            """
        }

        assertEquals(data["echoFiles"], listOf("Hello", "World"))
    }

    @Test
    fun `generated schema should handle any Map (using HashMap) types as property maps`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                propertyHashMapItems {
                    name
                    age
                }
            }
            """
        }

        assertEquals(data["propertyHashMapItems"], listOf(mapOf("name" to "bob", "age" to 55)))
    }

    @Test
    fun `generated schema should handle any Map (using SortedMap) types as property maps`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                propertySortedMapItems {
                    name
                    age
                }
            }
            """
        }

        assertEquals(data["propertySortedMapItems"], listOf(
            mapOf("name" to "Arthur", "age" to 76),
            mapOf("name" to "Jane", "age" to 28)
        ))
    }

    // In this test a dictionary entry for the schema type ComplexMapItem is defined
    // so that it is possible for a POJO mapping to be known since the ComplexMapItem is contained
    // in a property map (i.e. Map<String, Object>) and so the normal resolver and schema traversal code
    // will not be able to find the POJO since it does not exist as a strongly typed object in
    // resolver/POJO graph.
    @Test
    fun `generated schema should handle Map types as property maps when containing complex data`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                propertyMapWithComplexItems {
                    nameId {
                        id
                    }
                    age
                }
            }
            """
        }

        assertEquals(data["propertyMapWithComplexItems"], listOf(mapOf("nameId" to mapOf("id" to 150), "age" to 72)))
    }

    // This behavior is consistent with PropertyDataFetcher
    @Test
    fun `property map returns null when a property is not defined`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                propertyMapMissingNamePropItems {
                    name
                    age
                }
            }
            """
        }

        assertEquals(data["propertyMapMissingNamePropItems"], listOf(mapOf("name" to null, "age" to 55)))
    }

    // In this test a dictonary entry for the schema type NestedComplexMapItem is defined
    // however we expect to not be required to define one for the transitive UndiscoveredItem object since
    // the schema resolver discovery code should still be able to automatically determine the POJO that
    // maps to this schema type.
    @Test
    fun `generated schema should continue to associate resolvers for transitive types of a Map complex data type`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                propertyMapWithNestedComplexItems {
                    nested {
                        item {
                            id
                        }
                    }
                    age
                }
            }
            """
        }

        assertEquals(data["propertyMapWithNestedComplexItems"], listOf(mapOf("nested" to mapOf("item" to mapOf("id" to 63)), "age" to 72)))
    }

    @Test
    fun `generated schema should handle optional arguments`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                missing: itemsWithOptionalInput {
                    id
                }

                present: itemsWithOptionalInput(itemsInput: {name: "item1"}) {
                    id
                }
            }
            """
        }

        assertEquals(data["missing"], listOf(mapOf("id" to 0), mapOf("id" to 1)))
        assertEquals(data["present"], listOf(mapOf("id" to 0)))
    }

    @Test
    fun `generated schema should handle optional arguments using Optional`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                missing: itemsWithOptionalInputExplicit {
                    id
                }

                present: itemsWithOptionalInputExplicit(itemsInput: {name: "item1"}) {
                    id
                }
            }
            """
        }

        assertEquals(data["missing"], listOf(mapOf("id" to 0), mapOf("id" to 1)))
        assertEquals(data["present"], listOf(mapOf("id" to 0)))
    }

    @Test
    fun `generated schema should handle optional return types using Optional`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                missing: optionalItem(itemsInput: {name: "item?"}) {
                    id
                }

                present: optionalItem(itemsInput: {name: "item1"}) {
                    id
                }
            }
            """
        }

        assertNull(data["missing"])
        assertNotNull(data["present"])
    }

    @Test
    fun `generated schema should pass default arguments`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                defaultArgument
            }
            """
        }

        assert(data["defaultArgument"] == true)
    }

    @Test
    fun `introspection shouldn't fail for arguments of type list with a default value (defaultEnumListArgument)`() {
        val data = assertNoGraphQlErrors(gql) {
            """
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
            """
        }

        assertNotNull(data["__type"])
    }

    @Test
    fun `generated schema should return null without errors for null value with nested fields`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                complexNullableType {
                    first
                    second
                    third
                }
            }
            """
        }

        assert(data.containsKey("complexNullableType"))
        assertNull(data["complexNullableType"])
    }

    @Test
    fun `generated schema handles nested lists in input type fields`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                complexInputType(complexInput: [[{first: "foo", second: [[{first: "bar"}]]}]])
            }
            """
        }

        assertNotNull(data["complexInputType"])
    }

    @Test
    fun `generated schema should use type extensions`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                extendedType {
                    first
                    second
                }
            }
            """
        }

        assertEquals(data["extendedType"], mapOf(
            "first" to "test",
            "second" to "test"
        ))
    }

    @Test
    fun `generated schema uses properties if no methods are found`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                propertyField
            }
            """
        }

        assertNotNull(data["propertyField"])
    }

    @Test
    fun `generated schema allows enums in input types`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                enumInputType(type: TYPE_2)
            }
            """
        }

        assertEquals(data["enumInputType"], "TYPE_2")
    }

    @Test
    fun `generated schema works with custom scalars as input values`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                customScalarMapInputType(customScalarMap: { test: "me" })
            }
            """
        }

        assertEquals(data["customScalarMapInputType"], mapOf("test" to "me"))
    }

    @Test
    fun `generated schema should handle extended input types`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            mutation {
                saveUser(input: {name: "John", password: "secret"})
            }
            """
        }

        assertEquals(data["saveUser"], "John/secret")
    }

    @Test
    fun `generated schema supports generic properties`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                itemWithGenericProperties {
                    keys
                }
            }
            """
        }

        assertEquals(data["itemWithGenericProperties"], mapOf("keys" to listOf("A", "B")))
    }

    @Test
    fun `generated schema supports overriding built-in scalars`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                itemByBuiltInId(id: "38f685f1-b460-4a54-a17f-7fd69e8cf3f8") {
                    name
                }
            }
            """
        }

        assertNotNull(data["itemByBuiltInId"])
    }

    @Test
    fun `generated schema supports DataFetcherResult`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                dataFetcherResult {
                    name
                }
            }
            """
        }

        assertEquals(data["dataFetcherResult"], mapOf("name" to "item1"))
    }

    @Test
    fun `generated schema supports list of DataFetcherResult`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                dataFetcherResultItems {
                    name
                }
            }
            """
        }

        assertEquals(data["dataFetcherResultItems"], listOf(mapOf("name" to "item1")))
    }

    @Test
    fun `generated schema supports Kotlin suspend functions`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                coroutineItems {
                    id
                    name
                }
            }
            """
        }

        assertEquals(data["coroutineItems"], listOf(
            mapOf("id" to 0, "name" to "item1"),
            mapOf("id" to 1, "name" to "item2")
        ))
    }

    @Test
    fun `generated schema supports Kotlin coroutine channels for the subscription query`() {
        val newItem = Item(1, "item", Type.TYPE_1, UUID.randomUUID(), listOf())
        val closure = {
            """
            subscription {
                onItemCreatedCoroutineChannel {
                    id
                }
            }
            """
        }

        val result = gql.execute(ExecutionInput.newExecutionInput()
            .query(closure.invoke())
            .graphQLContext(mapOf("newItem" to newItem))
            .variables(mapOf()))

        val data = result.getData() as Publisher<ExecutionResult>
        val subscriber = TestEnvironment().newManualSubscriber(data)

        val subscriberResult = subscriber.requestNextElement() as ExecutionResultImpl
        val subscriberData = subscriberResult.getData() as Map<String, Map<String, Any>>?
        assert(result.errors.isEmpty())
        assertEquals(subscriberData?.get("onItemCreatedCoroutineChannel"), mapOf("id" to 1))
        subscriber.expectCompletion()
    }

    @Test
    fun `generated schema supports Kotlin coroutine channels with suspend function for the subscription query`() {
        val newItem = Item(1, "item", Type.TYPE_1, UUID.randomUUID(), listOf())
        val closure = {
            """
            subscription {
                onItemCreatedCoroutineChannelAndSuspendFunction {
                    id
                }
            }
            """
        }

        val result = gql.execute(ExecutionInput.newExecutionInput()
            .query(closure.invoke())
            .graphQLContext(mapOf("newItem" to newItem))
            .variables(mapOf()))

        val data = result.getData() as Publisher<ExecutionResult>
        val subscriber = TestEnvironment().newManualSubscriber(data)

        val subscriberResult = subscriber.requestNextElement() as ExecutionResultImpl
        val subscriberData = subscriberResult.getData() as Map<String, Map<String, Any>>?
        assert(result.errors.isEmpty())
        assertEquals(subscriberData?.get("onItemCreatedCoroutineChannelAndSuspendFunction"), mapOf("id" to 1))
        subscriber.expectCompletion()
    }

    @Test
    fun `generated schema supports arrays`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            {
                arrayItems {
                    name
                }
            }
            """
        }

        assertEquals(data["arrayItems"], listOf(
            mapOf("name" to "item1"),
            mapOf("name" to "item2")
        ))
    }

    @Test
    fun `generated schema should re-throw original runtime exception when executing a resolver method`() {
        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            {
                throwsIllegalArgumentException
            }
            """
        ))

        assertEquals(result.errors.size, 1)
        val exceptionWhileDataFetching = result.errors[0] as ExceptionWhileDataFetching
        assert(exceptionWhileDataFetching.exception is IllegalArgumentException)
    }

    class Transformer : GraphQLTypeVisitorStub() {
        override fun visitGraphQLObjectType(node: GraphQLObjectType?, context: TraverserContext<GraphQLSchemaElement>?): TraversalControl {
            val newNode = node?.transform { builder -> builder.description(node.description + " [MODIFIED]") }
            return changeNode(context, newNode)
        }
    }

    @Test
    fun `transformed schema should execute query`() {
        val transformedSchema = SchemaTransformer().transform(schema, Transformer())
        val transformedGql: GraphQL = GraphQL.newGraphQL(transformedSchema)
                .queryExecutionStrategy(AsyncExecutionStrategy())
                .build()

        val data = assertNoGraphQlErrors(transformedGql) {
            """
            {
                otherUnionItems {
                    ... on Item {
                        itemId: id
                    }
                    ... on ThirdItem {
                       thirdItemId: id
                    }
                }
            }
            """
        }

        assertEquals(data["otherUnionItems"], listOf(
                mapOf("itemId" to 0),
                mapOf("itemId" to 1),
                mapOf("thirdItemId" to 100)
        ))
    }
}
