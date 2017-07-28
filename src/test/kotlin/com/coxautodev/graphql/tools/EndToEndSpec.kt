package com.coxautodev.graphql.tools

import graphql.language.StringValue
import graphql.schema.Coercing
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLScalarType
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

fun createSchema() = SchemaParser.newParser()
    .schemaString(schemaDefinition)
    .resolvers(Query(), Mutation(), Subscription(), ItemResolver(), UnusedRootResolver(), UnusedResolver())
    .scalars(CustomUUIDScalar)
    .dictionary("OtherItem", OtherItemWithWrongName::class.java)
    .build()
    .makeExecutableSchema()

val schemaDefinition = """

scalar UUID

type Query {
    # Check if items list is empty
    empty: Boolean!
    # Get items by name
    items(itemsInput: ItemSearchInput!): [Item!]
    optionalItem(itemsInput: ItemSearchInput!): Item
    allItems: [AllItems!]
    itemsByInterface: [ItemInterface!]
    itemByUUID(uuid: UUID!): Item
    itemsWithOptionalInput(itemsInput: ItemSearchInput): [Item!]
    itemsWithOptionalInputExplicit(itemsInput: ItemSearchInput): [Item!]

    defaultArgument(arg: Boolean = true): Boolean!

    listList: [[String!]!]!
    futureItems: [Item!]!
    complexNullableType: ComplexNullable

    complexInputType(complexInput: [[ComplexInputType!]]): String!
    extendedType: ExtendedType!
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

class Query: GraphQLQueryResolver, ListListResolver<String>() {
    fun isEmpty() = items.isEmpty()
    fun items(input: ItemSearchInput): List<Item> = items.filter { it.name == input.name }
    fun optionalItem(input: ItemSearchInput) = items(input).firstOrNull()?.let { Optional.of(it) } ?: Optional.empty()
    fun allItems(): List<Any> = items + otherItems
    fun itemsByInterface(): List<ItemInterface> = items + otherItems
    fun itemByUUID(uuid: UUID): Item? = items.find { it.uuid == uuid }
    fun itemsWithOptionalInput(input: ItemSearchInput?) = if(input == null) items else items(input)
    fun itemsWithOptionalInputExplicit(input: Optional<ItemSearchInput>) = if(input.isPresent) items(input.get()) else items

    fun defaultArgument(arg: Boolean) = arg

    fun futureItems() = CompletableFuture.completedFuture(items)
    fun complexNullableType(): ComplexNullable? = null

    fun complexInputType(input: List<List<ComplexInputType>?>?) = input?.firstOrNull()?.firstOrNull()?.let { it.first == "foo" && it.second?.firstOrNull()?.firstOrNull()?.first == "bar" } ?: false
    fun extendedType() = ExtendedType()
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
        return Item(items.size, input.name, input.type, UUID.randomUUID(), listOf()).apply {
            items.add(this)
        }
    }
}

class OnItemCreatedContext(val newItem: Item)

class Subscription : GraphQLSubscriptionResolver {
    fun onItemCreated(env: DataFetchingEnvironment): Item {
        return env.getContext<OnItemCreatedContext>().newItem
    }
}

class ItemResolver : GraphQLResolver<Item> {
    fun tags(item: Item, names: List<String>?): List<Tag> = item.tags.filter { names?.contains(it.name) ?: true }
}

interface ItemInterface {
    val name: String
    val type: Type
    val uuid: UUID
}

enum class Type { TYPE_1, TYPE_2 }
data class Item(val id: Int, override val name: String, override val type: Type, override val uuid:UUID, val tags: List<Tag>) : ItemInterface
data class OtherItemWithWrongName(val id: Int, override val name: String, override val type: Type, override val uuid:UUID) : ItemInterface
data class Tag(val id: Int, val name: String)
data class ItemSearchInput(val name: String)
data class NewItemInput(val name: String, val type: Type)
data class ComplexNullable(val first: String, val second: String, val third: String)
data class ComplexInputType(val first: String, val second: List<List<ComplexInputTypeTwo>?>?)
data class ComplexInputTypeTwo(val first: String)

val CustomUUIDScalar = GraphQLScalarType("UUID", "UUID", object : Coercing<UUID, String> {

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
