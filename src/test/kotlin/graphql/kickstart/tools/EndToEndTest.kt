package graphql.kickstart.tools

import graphql.*
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLSchema
import org.junit.Before
import org.junit.Test
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.reactivestreams.tck.TestEnvironment
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EndToEndTest {

    private lateinit var gql: GraphQL
    private val schema: GraphQLSchema = createSchema()

    @Before
    fun setup() {
        gql = GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(AsyncExecutionStrategy())
                .build()
    }

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

        assert(data["addItem"] != null)
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
                .context(OnItemCreatedContext(newItem))
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
        assert(returnedItem?.get("onItemCreated")?.get("id") == 1)
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

        assert(data["itemsByInterface"] != null)
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

        assert(data["allItems"] != null)
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

        val items = data["nestedUnionItems"] as List<Map<String, Int>>
        assert(items[0]["itemId"] == 0)
        assert(items[1]["itemId"] == 1)
        assert(items[2]["otherItemId"] == 0)
        assert(items[3]["otherItemId"] == 1)
        assert(items[4]["thirdItemId"] == 100)
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

        assert(data["itemByUUID"] != null)
    }

    @Test
    fun `generated schema should handle non nullable scalar types`() {
        val fileParts = listOf(MockPart("test.doc", "Hello"), MockPart("test.doc", "World"))
        val args = mapOf("fileParts" to fileParts)
        val data = assertNoGraphQlErrors(gql, args) {
            """
            mutation (${'$'}fileParts: [Upload!]!) { echoFiles(fileParts: ${'$'}fileParts)}
            """
        }

        assert((data["echoFiles"] as ArrayList<String>).joinToString(",") == "Hello,World")
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

        assert(data["propertyHashMapItems"] == listOf(mapOf("name" to "bob", "age" to 55)))
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

        assert(data["propertySortedMapItems"] == listOf(
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

        assert(data["propertyMapWithComplexItems"] == listOf(mapOf("nameId" to mapOf("id" to 150), "age" to 72)))
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

        assert(data["propertyMapMissingNamePropItems"] == listOf(mapOf("name" to null, "age" to 55)))
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

        assert(data["propertyMapWithNestedComplexItems"] == listOf(mapOf("nested" to mapOf("item" to mapOf("id" to 63)), "age" to 72)))
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

        assert((data["missing"] as List<*>).size > 1)
        assert((data["present"] as List<*>).size == 1)
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

        assert((data["missing"] as List<*>).size > 1)
        assert((data["present"] as List<*>).size == 1)
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

        assert(data["missing"] == null)
        assert(data["present"] != null)
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

        assert(data["__type"] != null)
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

        assert((data as Map<*, *>).containsKey("complexNullableType"))
        assert(data["complexNullableType"] == null)
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

        assert(data["complexInputType"] != null)
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

        assert(data["extendedType"] != null)
        assert((data["extendedType"] as Map<*, *>)["first"] != null)
        assert((data["extendedType"] as Map<*, *>)["second"] != null)
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

        assert(data["propertyField"] != null)
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

        assert(data["enumInputType"] == "TYPE_2")
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

        assert(data["customScalarMapInputType"] == mapOf("test" to "me"))
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

        assert(data["saveUser"] == "John/secret")
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

        assert(data["itemWithGenericProperties"] == mapOf("keys" to listOf("A", "B")))
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

        assert(data["itemByBuiltInId"] != null)
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

        assert((data["dataFetcherResult"] as Map<*, *>)["name"] == "item1")
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

        assert(data["coroutineItems"] == listOf(
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
                .context(OnItemCreatedContext(newItem))
                .variables(mapOf()))

        val data = result.getData() as Publisher<ExecutionResult>
        val subscriber = TestEnvironment().newManualSubscriber(data)

        val subscriberResult = subscriber.requestNextElement() as ExecutionResultImpl
        val subscriberData = subscriberResult.getData() as Map<String, Map<String, Any>>?
        assert(result.errors.isEmpty())
        assert(subscriberData?.get("onItemCreatedCoroutineChannel")?.get("id") == 1)
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
                .context(OnItemCreatedContext(newItem))
                .variables(mapOf()))

        val data = result.getData() as Publisher<ExecutionResult>
        val subscriber = TestEnvironment().newManualSubscriber(data)

        val subscriberResult = subscriber.requestNextElement() as ExecutionResultImpl
        val subscriberData = subscriberResult.getData() as Map<String, Map<String, Any>>?
        assert(result.errors.isEmpty())
        assert(subscriberData?.get("onItemCreatedCoroutineChannelAndSuspendFunction")?.get("id") == 1)
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

        assert((data["arrayItems"] as List<*>).filterIsInstance<Map<*, *>>().map { it["name"] } == listOf("item1", "item2"))
    }

    @Test
    fun `generated schema should re-throw original runtime exception when executing a resolver method`() {
        val result = gql.execute(ExecutionInput.newExecutionInput().query("""
            {
                throwsIllegalArgumentException
            }
        """
        ))

        assert(result.errors.size == 1)
        assert((result.errors[0] as ExceptionWhileDataFetching).exception is IllegalArgumentException)
    }
}
