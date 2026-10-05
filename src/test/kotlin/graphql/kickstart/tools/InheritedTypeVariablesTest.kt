package graphql.kickstart.tools

import graphql.GraphQL
import org.junit.Assert.assertThrows
import org.junit.Test

class InheritedTypeVariablesTest {

    @Test
    fun `type variables passed through several superclasses are resolved`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    item: Item!
                    ownerItem: OwnerItem!
                }

                type Item {
                    id: ID!
                    value: ID!
                }

                type OwnerItem {
                    id: Owner!
                    value: Owner!
                }

                type Owner {
                    name: String!
                }
                """)
            .resolvers(QueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                item { id value }
                ownerItem {
                    id { name }
                    value { name }
                }
            }
            """
        }

        assertEquals(data["item"], mapOf("id" to "1", "value" to "1"))
        assertEquals(data["ownerItem"], mapOf("id" to mapOf("name" to "owner"), "value" to mapOf("name" to "owner")))
    }

    @Test
    fun `type variables renamed and reordered by superclasses are resolved`() {
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
            .resolvers(QueryResolver())
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

        assertEquals(data["account"], mapOf("id" to "2", "owner" to mapOf("name" to "owner")))
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
                    nodes: [Owner]!
                    entries: [OwnerEntry!]!
                }

                type OwnerEdge {
                    node: Owner!
                }

                type OwnerEntry {
                    position: Int!
                    node: Owner!
                }

                type Owner {
                    name: String!
                }
                """)
            .resolvers(QueryResolver())
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
                    nodes { name }
                    entries {
                        position
                        node { name }
                    }
                }
            }
            """
        }

        val owner = mapOf("name" to "owner")
        assertEquals(data["owners"], mapOf(
            "edges" to listOf(mapOf("node" to owner)),
            "nodes" to listOf(owner),
            "entries" to listOf(mapOf("position" to 0, "node" to owner))
        ))
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
    fun `generic types bound differently by subclasses can't share a type`() {
        val error = assertThrows(SchemaClassScannerError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        ownerPage: OwnerPage!
                        accountPage: AccountPage!
                    }

                    type OwnerPage {
                        meta: Meta!
                    }

                    type AccountPage {
                        meta: Meta!
                    }

                    type Meta {
                        total: Int!
                    }
                    """)
                .resolvers(QueryResolver())
                .build()
                .makeExecutableSchema()
        }

        val message = error.message.orEmpty()
        assert(message.startsWith("Two different classes used for type Meta")) { message }
        assert(message.contains("${Meta::class.java.name}<${Owner::class.java.name}>")) { message }
        assert(message.contains("${Meta::class.java.name}<${Account::class.java.name}>")) { message }
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

    class QueryResolver : GraphQLQueryResolver {
        fun item(): Item = Item(1)
        fun ownerItem(): BaseItem<Owner> = BaseItem(Owner("owner"))
        fun account(): Account = Account(2, Owner("owner"))
        fun owners(): OwnerConnection = OwnerConnection(listOf(Owner("owner")))
        fun ownerPage(): OwnerPage = OwnerPage()
        fun accountPage(): AccountPage = AccountPage()
    }

    // id is a public field, value a getter
    open class AbstractItem<T>(@JvmField val id: T, val value: T)

    open class BaseItem<T>(value: T) : AbstractItem<T>(value, value)

    class Item(value: Long) : BaseItem<Long>(value)

    interface Identifiable<I> {
        val id: I
    }

    abstract class Entity<K, R>(override val id: K, val owner: R) : Identifiable<K>

    // passes its K as Entity's R and vice versa
    abstract class SwappedEntity<K, R>(id: R, owner: K) : Entity<R, K>(id, owner)

    class Account(id: Long, owner: Owner) : SwappedEntity<Owner, Long>(id, owner)

    abstract class Connection<T>(val nodes: List<@JvmWildcard T>) {
        val edges: List<Edge<T>>
            get() = nodes.map { Edge(it) }

        val entries: List<Entry>
            get() = nodes.mapIndexed { position, node -> Entry(position, node) }

        inner class Entry(val position: Int, val node: T)
    }

    class Edge<T>(val node: T)

    class OwnerConnection(nodes: List<Owner>) : Connection<Owner>(nodes)

    class Meta<T>(val total: Int)

    abstract class MetaPage<T> {
        val meta: Meta<T> = Meta(0)
    }

    class OwnerPage : MetaPage<Owner>()

    class AccountPage : MetaPage<Account>()

    open class GenericMethodBase<T>

    // the class binds its own T, which doesn't make the method's T resolvable
    class GenericMethodQueryResolver : GenericMethodBase<Owner>(), GraphQLQueryResolver {
        @Suppress("UNCHECKED_CAST")
        fun <T> owner(): T = Owner("owner") as T
    }

    class Owner(val name: String)
}
