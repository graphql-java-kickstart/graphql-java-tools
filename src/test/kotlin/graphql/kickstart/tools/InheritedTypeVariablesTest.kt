package graphql.kickstart.tools

import graphql.GraphQL
import org.junit.Assert.assertThrows
import org.junit.Test

class InheritedTypeVariablesTest {

    @Test
    fun `type variables passed through several superclasses are resolved for fields`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    item: Item!
                    holder: Holder!
                }

                type Item {
                    id: ID!
                }

                type Holder {
                    value: Owner!
                }

                type Owner {
                    name: String!
                }
                """)
            .resolvers(FieldItemQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                item { id }
                holder { value { name } }
            }
            """
        }

        assertEquals(data["item"], mapOf("id" to "1"))
        assertEquals(data["holder"], mapOf("value" to mapOf("name" to "owner")))
    }

    @Test
    fun `type variables passed through several superclasses are resolved for getters`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    item: Item!
                    holder: Holder!
                }

                type Item {
                    id: ID!
                }

                type Holder {
                    value: Owner!
                }

                type Owner {
                    name: String!
                }
                """)
            .resolvers(GetterItemQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                item { id }
                holder { value { name } }
            }
            """
        }

        assertEquals(data["item"], mapOf("id" to "2"))
        assertEquals(data["holder"], mapOf("value" to mapOf("name" to "owner")))
    }

    @Test
    fun `type variables renamed by superclasses are resolved`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    account: Account!
                }

                type Account {
                    id: ID!
                    owner: Owner!
                }

                type Owner {
                    name: String!
                }
                """)
            .resolvers(AccountQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                account {
                    id
                    owner { name }
                }
            }
            """
        }

        assertEquals(data["account"], mapOf("id" to "3", "owner" to mapOf("name" to "owner")))
    }

    @Test
    fun `type variables reordered by a superclass are resolved`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    tuple: Tuple!
                }

                type Tuple {
                    first: Owner!
                    second: Tag!
                }

                type Owner {
                    name: String!
                }

                type Tag {
                    label: String!
                }
                """)
            .resolvers(TupleQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                tuple {
                    first { name }
                    second { label }
                }
            }
            """
        }

        assertEquals(data["tuple"], mapOf("first" to mapOf("name" to "owner"), "second" to mapOf("label" to "tag")))
    }

    @Test
    fun `type variables nested in types inherited from a superclass are resolved`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    owners: OwnerConnection!
                }

                type OwnerConnection {
                    edges: [OwnerEdge!]!
                }

                type OwnerEdge {
                    node: Owner!
                }

                type Owner {
                    name: String!
                }
                """)
            .resolvers(ConnectionQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                owners {
                    edges {
                        node { name }
                    }
                }
            }
            """
        }

        assertEquals(data["owners"], mapOf("edges" to listOf(mapOf("node" to mapOf("name" to "owner")))))
    }

    @Test
    fun `type variables leaked from raw types don't overflow the stack`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    tree: Tree!
                }

                type Tree {
                    grouped: GroupedTree!
                    async: AsyncTree!
                }

                type GroupedTree {
                    value: [String!]!
                }

                type AsyncTree {
                    value: String!
                }
                """)
            .resolvers(RawGenericFixtures.QueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                tree {
                    grouped { value }
                    async { value }
                }
            }
            """
        }

        assertEquals(data["tree"], mapOf("grouped" to mapOf("value" to listOf("leaf")), "async" to mapOf("value" to "leaf")))
    }

    @Test
    fun `type variables in wildcard bounds are resolved`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    owners: OwnerList!
                }

                type OwnerList {
                    items: [Owner]!
                }

                type Owner {
                    name: String!
                }
                """)
            .resolvers(WildcardQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                owners {
                    items { name }
                }
            }
            """
        }

        assertEquals(data["owners"], mapOf("items" to listOf(mapOf("name" to "owner"))))
    }

    @Test
    fun `type variables of outer classes are resolved`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    owners: OwnerListing!
                }

                type OwnerListing {
                    entries: [OwnerEntry!]!
                }

                type OwnerEntry {
                    position: Int!
                    value: Owner!
                }

                type Owner {
                    name: String!
                }
                """)
            .resolvers(ListingQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                owners {
                    entries {
                        position
                        value { name }
                    }
                }
            }
            """
        }

        assertEquals(data["owners"], mapOf("entries" to listOf(mapOf("position" to 0, "value" to mapOf("name" to "owner")))))
    }

    @Test
    fun `generic types bound differently by subclasses can't share a type`() {
        val error = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        owners: OwnerPage!
                        tags: TagPage!
                    }

                    type OwnerPage {
                        meta: Meta!
                    }

                    type TagPage {
                        meta: Meta!
                    }

                    type Meta {
                        total: Int!
                    }
                    """)
                .resolvers(MetaQueryResolver())
                .build()
                .makeExecutableSchema()
        }

        val message = error.message.orEmpty()
        assert(message.startsWith("Two different classes used for type Meta")) { message }
        assert(message.contains("${Meta::class.java.name}<${Owner::class.java.name}>")) { message }
        assert(message.contains("${Meta::class.java.name}<${Tag::class.java.name}>")) { message }
    }

    @Test
    fun `type variables of generic methods can't be resolved`() {
        val error = assertThrows(IllegalStateException::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        owner: Owner!
                    }

                    type Owner {
                        name: String!
                    }
                    """)
                .resolvers(GenericMethodQueryResolver())
                .build()
                .makeExecutableSchema()
        }

        assert(error.message.orEmpty().startsWith("Could not resolve type variable")) { error.message.orEmpty() }
    }

    class FieldItemQueryResolver : GraphQLQueryResolver {
        fun item(): FieldItem = FieldItem(1)
        fun holder(): FieldHolder<Owner> = FieldHolder(Owner("owner"))
    }

    open class AbstractFieldItem<T>(@JvmField val id: T)

    open class BaseFieldItem<T>(id: T) : AbstractFieldItem<T>(id)

    class FieldItem(id: Long) : BaseFieldItem<Long>(id)

    open class BaseFieldHolder<U>(@JvmField val value: U)

    class FieldHolder<T>(value: T) : BaseFieldHolder<T>(value)

    class GetterItemQueryResolver : GraphQLQueryResolver {
        fun item(): GetterItem = GetterItem(2)
        fun holder(): OwnerHolder = OwnerHolder(Owner("owner"))
    }

    open class AbstractGetterItem<T>(val id: T)

    open class BaseGetterItem<T>(id: T) : AbstractGetterItem<T>(id)

    class GetterItem(id: Long) : BaseGetterItem<Long>(id)

    open class AbstractHolder<T>(val value: T)

    open class BaseHolder<T>(value: T) : AbstractHolder<T>(value)

    class OwnerHolder(value: Owner) : BaseHolder<Owner>(value)

    class AccountQueryResolver : GraphQLQueryResolver {
        fun account(): Account = Account(3, Owner("owner"))
    }

    interface Identifiable<I> {
        val id: I
    }

    abstract class OwnedEntity<K, R>(override val id: K, val owner: R) : Identifiable<K>

    abstract class AuditableEntity<E, O>(id: E, owner: O) : OwnedEntity<E, O>(id, owner)

    class Account(id: Long, owner: Owner) : AuditableEntity<Long, Owner>(id, owner)

    class TupleQueryResolver : GraphQLQueryResolver {
        fun tuple(): OwnerTagTuple = OwnerTagTuple(Owner("owner"), Tag("tag"))
    }

    open class Tuple<A, B>(val first: A, val second: B)

    open class ReversedTuple<A, B>(first: B, second: A) : Tuple<B, A>(first, second)

    class OwnerTagTuple(first: Owner, second: Tag) : ReversedTuple<Tag, Owner>(first, second)

    class ConnectionQueryResolver : GraphQLQueryResolver {
        fun owners(): OwnerConnection = OwnerConnection(listOf(Edge(Owner("owner"))))
    }

    abstract class Connection<T>(val edges: List<Edge<T>>)

    class Edge<T>(val node: T)

    class OwnerConnection(edges: List<Edge<Owner>>) : Connection<Owner>(edges)

    class WildcardQueryResolver : GraphQLQueryResolver {
        fun owners(): OwnerList = OwnerList(listOf(Owner("owner")))
    }

    abstract class WildcardList<T>(val items: List<@JvmWildcard T>)

    class OwnerList(items: List<Owner>) : WildcardList<Owner>(items)

    class ListingQueryResolver : GraphQLQueryResolver {
        fun owners(): OwnerListing = OwnerListing(listOf(Owner("owner")))
    }

    abstract class Listing<T>(private val values: List<T>) {
        val entries: List<Entry>
            get() = values.mapIndexed { position, value -> Entry(position, value) }

        inner class Entry(val position: Int, val value: T)
    }

    class OwnerListing(values: List<Owner>) : Listing<Owner>(values)

    class MetaQueryResolver : GraphQLQueryResolver {
        fun owners(): OwnerPage = OwnerPage()
        fun tags(): TagPage = TagPage()
    }

    class Meta<T>(val total: Int)

    abstract class MetaPage<T> {
        val meta: Meta<T> = Meta(0)
    }

    class OwnerPage : MetaPage<Owner>()

    class TagPage : MetaPage<Tag>()

    open class GenericMethodBase<T>

    // the class binds its own T, which doesn't make the method's T resolvable
    class GenericMethodQueryResolver : GenericMethodBase<Owner>(), GraphQLQueryResolver {
        @Suppress("UNCHECKED_CAST")
        fun <T> owner(): T = Owner("owner") as T
    }

    class Owner(val name: String)

    class Tag(val label: String)
}
