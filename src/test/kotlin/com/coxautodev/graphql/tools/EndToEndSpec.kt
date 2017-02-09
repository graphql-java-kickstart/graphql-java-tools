package com.coxautodev.graphql.tools

val schemaDefinition = """
type Query {
    items(itemsInput: ItemSearchInput!): [Item!] @doc(d: "Get items by name")
    allItems: [AllItems!]
    itemsByInterface: [ItemInterface!]
}

type Mutation {
    addItem(newItem: NewItemInput!): Item!
}

input ItemSearchInput {
    name: String! @doc(d: "The item name to look for")
}

input NewItemInput {
    name: String!
    type: Type!
}

enum Type {
    TYPE_1 @doc(d: "Item type 1")
    TYPE_2 @doc(d: "Item type 2")
}

type Item implements ItemInterface {
    id: Int!
    name: String!
    type: Type!
    tags(names: [String!]): [Tag!]
}

type OtherItem implements ItemInterface {
    id: Int!
    name: String!
    type: Type!
}

interface ItemInterface {
    name: String!
    type: Type!
}

union AllItems = Item | OtherItem

type Tag {
    id: Int!
    name: String!
}
"""

val items = mutableListOf(
    Item(0, "item1", Type.TYPE_1, listOf(Tag(1, "item1-tag1"), Tag(2, "item1-tag2"))),
    Item(1, "item2", Type.TYPE_2, listOf(Tag(3, "item2-tag1"), Tag(4, "item2-tag2")))
)

val otherItems = mutableListOf(
    OtherItem(0, "otherItem1", Type.TYPE_1),
    OtherItem(1, "otherItem2", Type.TYPE_2)
)

class Query : GraphQLRootResolver() {
    fun items(input: ItemSearchInput): List<Item> = items.filter { it.name == input.name }
    fun allItems(): List<Any> = items + otherItems
    fun itemsByInterface(): List<ItemInterface> = items + otherItems
}

class Mutation: GraphQLRootResolver() {
    fun addItem(input: NewItemInput): Item {
        return Item(items.size, input.name, input.type, listOf()).apply {
            items.add(this)
        }
    }
}

class ItemResolver : GraphQLResolver(Item::class.java) {
    fun tags(item: Item, names: List<String>?): List<Tag> = item.tags.filter { names?.contains(it.name) ?: true }
}

interface ItemInterface {
    val name: String
    val type: Type
}

enum class Type { TYPE_1, TYPE_2 }
data class Item(val id: Int, override val name: String, override val type: Type, val tags: List<Tag>) : ItemInterface
data class OtherItem(val id: Int, override val name: String, override val type: Type) : ItemInterface
data class Tag(val id: Int, val name: String)
data class ItemSearchInput(val name: String)
data class NewItemInput(val name: String, val type: Type)

