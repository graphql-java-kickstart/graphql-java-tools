package com.coxautodev.graphql.tools

import graphql.execution.DataFetcherResult
import graphql.execution.batched.Batched
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.schema.Coercing
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLScalarType
import org.reactivestreams.Publisher
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

fun createSchema() = SchemaParser.newParser()
    .schemaString(schemaDefinition)
    .resolvers(Query(), Mutation(), Subscription(), ItemResolver(), UnusedRootResolver(), UnusedResolver())
    .scalars(customScalarUUID, customScalarMap, customScalarId)
    .dictionary("OtherItem", OtherItemWithWrongName::class)
    .dictionary("ThirdItem", ThirdItem::class)
    .build()
    .makeExecutableSchema()

val schemaDefinition = """

## Private comment!
scalar UUID
scalar customScalarMap

type Query {
    # Check if items list is empty
    empty: Boolean!
    # Get all items
    allBaseItems: [Item!]
    # Get items by name
    items(itemsInput: ItemSearchInput!): [Item!]
    optionalItem(itemsInput: ItemSearchInput!): Item
    allItems: [AllItems!]
    otherUnionItems: [OtherUnion!]
    nestedUnionItems: [NestedUnion!]
    itemsByInterface: [ItemInterface!]
    itemByUUID(uuid: UUID!): Item
    itemByBuiltInId(id: ID!): Item
    itemsWithOptionalInput(itemsInput: ItemSearchInput): [Item!]
    itemsWithOptionalInputExplicit(itemsInput: ItemSearchInput): [Item!]
    enumInputType(type: Type!): Type!
    customScalarMapInputType(customScalarMap: customScalarMap): customScalarMap

    defaultArgument(arg: Boolean = true): Boolean!
    defaultEnumListArgument(types: [Type] = [TYPE_1]): [Type]

    listList: [[String!]!]!
    futureItems: [Item!]!
    complexNullableType: ComplexNullable

    complexInputType(complexInput: [[ComplexInputType!]]): String!
    extendedType: ExtendedType!

    # Exercise field with get<<capitalised field name>> resolver
    itemsWithGetResolver: [Item!]

    # Check it's possible to use field names that correspond to methods on the java.lang.Object class
    class: [Item!]
    hashCode: [Item!]

    propertyField: String!
    dataFetcherResult: Item!
}

type ExtendedType {
    first: String!
}

extend type ExtendedType {
    second: String!
}

type ComplexNullable {
    first: String!
    second: String!
    third: String!
}

input ComplexInputType {
    first: String!
    second: [[ComplexInputTypeTwo!]]
}

input ComplexInputTypeTwo {
    first: String!
}

type Mutation {
    addItem(newItem: NewItemInput!): Item!
}

type Subscription {
    onItemCreated: Item!
}

input ItemSearchInput {
    # The item name to look for
    name: String!
}

input NewItemInput {
    name: String! @deprecated
    type: Type! @deprecated(reason: "This is a reason")
}

enum Type {
    # Item type 1
    TYPE_1
    # Item type 2
    TYPE_2
}

type Item implements ItemInterface {
    id: Int!
    name: String!
    type: Type!
    uuid: UUID!
    tags(names: [String!]): [Tag!]
    batchedName: String!
    batchedWithParamsTags(names: [String!]): [Tag!]
}

type OtherItem implements ItemInterface {
    id: Int!
    name: String!
    type: Type!
    uuid: UUID!
}

interface ItemInterface {
    name: String!
    type: Type!
    uuid: UUID!
}

union AllItems = Item | OtherItem

type ThirdItem {
    id: Int!
}

union OtherUnion = Item | ThirdItem

union NestedUnion = OtherUnion | OtherItem

type Tag {
    id: Int!
    name: String!
}
"""


val items = mutableListOf(
    Item(0, "item1", Type.TYPE_1, UUID.fromString("38f685f1-b460-4a54-a17f-7fd69e8cf3f8"), listOf(Tag(1, "item1-tag1"), Tag(2, "item1-tag2"))),
    Item(1, "item2", Type.TYPE_2, UUID.fromString("38f685f1-b460-4a54-b17f-7fd69e8cf3f8"), listOf(Tag(3, "item2-tag1"), Tag(4, "item2-tag2")))
)

val otherItems = mutableListOf(
    OtherItemWithWrongName(0, "otherItem1", Type.TYPE_1, UUID.fromString("38f685f1-b460-4a54-c17f-7fd69e8cf3f8")),
    OtherItemWithWrongName(1, "otherItem2", Type.TYPE_2, UUID.fromString("38f685f1-b460-4a54-d17f-7fd69e8cf3f8"))
)

val thirdItems = mutableListOf(
        ThirdItem(100)
)

class Query: GraphQLQueryResolver, ListListResolver<String>() {
    fun isEmpty() = items.isEmpty()
    fun allBaseItems() = items
    fun items(input: ItemSearchInput): List<Item> = items.filter { it.name == input.name }
    fun optionalItem(input: ItemSearchInput) = items(input).firstOrNull()?.let { Optional.of(it) } ?: Optional.empty()
    fun allItems(): List<Any> = items + otherItems
    fun otherUnionItems(): List<Any> = items + thirdItems
    fun nestedUnionItems(): List<Any> = items + otherItems + thirdItems
    fun itemsByInterface(): List<ItemInterface> = items + otherItems
    fun itemByUUID(uuid: UUID): Item? = items.find { it.uuid == uuid }
    fun itemByBuiltInId(id: UUID): Item? {
        return items.find { it.uuid == id }
    }
    fun itemsWithOptionalInput(input: ItemSearchInput?) = if(input == null) items else items(input)
    fun itemsWithOptionalInputExplicit(input: Optional<ItemSearchInput>) = if(input.isPresent) items(input.get()) else items
    fun enumInputType(type: Type) = type
    fun customScalarMapInputType(customScalarMap: Map<String, Any>) = customScalarMap

    fun defaultArgument(arg: Boolean) = arg
    fun defaultEnumListArgument(types: List<Type>) = types

    fun futureItems() = CompletableFuture.completedFuture(items)
    fun complexNullableType(): ComplexNullable? = null

    fun complexInputType(input: List<List<ComplexInputType>?>?) = input?.firstOrNull()?.firstOrNull()?.let { it.first == "foo" && it.second?.firstOrNull()?.firstOrNull()?.first == "bar" } ?: false
    fun extendedType() = ExtendedType()

    fun getItemsWithGetResolver() = items

    fun getFieldClass() = items
    fun getFieldHashCode() = items

    private val propertyField = "test"

    fun dataFetcherResult(): DataFetcherResult<Item> {
        return DataFetcherResult(items.first(), listOf())
    }
}

class UnusedRootResolver: GraphQLQueryResolver
class UnusedResolver: GraphQLResolver<String>

class ExtendedType {
    fun first() = "test"
    fun second() = "test"
}

abstract class ListListResolver<out E> {
    fun listList(): List<List<E>> = listOf(listOf())
}

class Mutation: GraphQLMutationResolver {
    fun addItem(input: NewItemInput): Item {
        return Item(items.size, input.name, input.type, UUID.randomUUID(), listOf()) // Don't actually add the item to the list, since we want the test to be deterministic
    }
}

class OnItemCreatedContext(val newItem: Item)

class Subscription : GraphQLSubscriptionResolver {
    fun onItemCreated(env: DataFetchingEnvironment) =
        Publisher<Item> { subscriber ->
            subscriber.onNext(env.getContext<OnItemCreatedContext>().newItem)
//            subscriber.onComplete()
        }
}

class ItemResolver : GraphQLResolver<Item> {
    fun tags(item: Item, names: List<String>?): List<Tag> = item.tags.filter { names?.contains(it.name) ?: true }

    @Batched
    fun batchedName(items: List<Item>) = items.map { it.name }

    @Batched
    fun batchedWithParamsTags(items: List<Item>, names: List<String>?): List<List<Tag>> = items.map{ it.tags.filter { names?.contains(it.name) ?: true } }
}

interface ItemInterface {
    val name: String
    val type: Type
    val uuid: UUID
}

enum class Type { TYPE_1, TYPE_2 }
data class Item(val id: Int, override val name: String, override val type: Type, override val uuid:UUID, val tags: List<Tag>) : ItemInterface
data class OtherItemWithWrongName(val id: Int, override val name: String, override val type: Type, override val uuid:UUID) : ItemInterface
data class ThirdItem(val id: Int)
data class Tag(val id: Int, val name: String)
data class ItemSearchInput(val name: String)
data class NewItemInput(val name: String, val type: Type)
data class ComplexNullable(val first: String, val second: String, val third: String)
data class ComplexInputType(val first: String, val second: List<List<ComplexInputTypeTwo>?>?)
data class ComplexInputTypeTwo(val first: String)

val customScalarId = GraphQLScalarType("ID", "Overrides built-in ID", object : Coercing<UUID, String> {
    override fun serialize(input: Any): String? = when (input) {
        is String -> input
        is UUID -> input.toString()
        else -> null
    }

    override fun parseValue(input: Any): UUID? = parseLiteral(input)

    override fun parseLiteral(input: Any): UUID? = when (input) {
        is StringValue -> UUID.fromString(input.value)
        else -> null
    }
})

val customScalarUUID = GraphQLScalarType("UUID", "UUID", object : Coercing<UUID, String> {

    override fun serialize(input: Any): String? = when (input) {
        is String -> input
        is UUID -> input.toString()
        else -> null
    }

    override fun parseValue(input: Any): UUID? = parseLiteral(input)

    override fun parseLiteral(input: Any): UUID? = when (input) {
        is StringValue -> UUID.fromString(input.value)
        else -> null
    }
})

val customScalarMap = GraphQLScalarType("customScalarMap", "customScalarMap", object: Coercing<Map<String, Any>, Map<String, Any>> {

    @Suppress("UNCHECKED_CAST")
    override fun parseValue(input: Any?): Map<String, Any> = input as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    override fun serialize(dataFetcherResult: Any?): Map<String, Any> = dataFetcherResult as Map<String, Any>

    override fun parseLiteral(input: Any?): Map<String, Any> = (input as ObjectValue).objectFields.associateBy { it.name }.mapValues { (it.value.value as StringValue).value }
})
