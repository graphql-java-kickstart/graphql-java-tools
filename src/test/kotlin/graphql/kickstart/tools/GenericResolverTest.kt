package graphql.kickstart.tools

import graphql.GraphQL
import graphql.kickstart.tools.resolver.FieldResolverError
import org.junit.Assert.assertThrows
import org.junit.Test

class GenericResolverTest {

    @Test
    fun `methods from generic resolvers are resolved`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    bar: Bar
                }
        
                type Bar {
                    value: String
                }
                """)
            .resolvers(QueryResolver1(), BarResolver())
            .build()
            .makeExecutableSchema()
    }

    class QueryResolver1 : GraphQLQueryResolver {
        fun getBar(): Bar {
            return Bar()
        }
    }

    class Bar

    abstract class FooResolver<T> : GraphQLResolver<T> {
        fun getValue(foo: T): String = "value"
    }

    class BarResolver : FooResolver<Bar>(), GraphQLResolver<Bar>

    @Test
    fun `methods from generic inherited resolvers are resolved`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    car: Car
                }
                type Car {
                    value: String
                }
                """)
            .resolvers(QueryResolver2(), CarResolver())
            .build()
            .makeExecutableSchema()
    }

    class QueryResolver2 : GraphQLQueryResolver {
        fun getCar(): Car = Car()
    }

    abstract class FooGraphQLResolver<T> : GraphQLResolver<T> {
        fun getValue(foo: T): String = "value"
    }

    class Car

    class CarResolver : FooGraphQLResolver<Car>()

    @Test
    fun `star projected resolvers are applied to parameterized data classes`() {
        val gql = GraphQL.newGraphQL(pageSchema(PageResolver())).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                page {
                    content { name }
                    size
                }
            }
            """
        }

        assertEquals(data["page"], mapOf("content" to listOf(mapOf("name" to "item")), "size" to 1))
    }

    @Test
    fun `supertype resolvers are applied to parameterized data classes`() {
        val gql = GraphQL.newGraphQL(pageSchema(CountableResolver())).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                page {
                    content { name }
                    size
                }
            }
            """
        }

        assertEquals(data["page"], mapOf("content" to listOf(mapOf("name" to "item")), "size" to 1))
    }

    @Test
    fun `resolvers for a specific parameterization of a data class are rejected`() {
        assertThrows(FieldResolverError::class.java) { pageSchema(ItemPageSourceResolver()) }

        val error = assertThrows(ResolverError::class.java) { pageSchema(ItemPageResolver()) }
        assertEquals(error.message, "Resolver '${ItemPageResolver::class.java.name}' may not have a parameterized type " +
            "(${Page::class.java.name}<${Item::class.java.name}>) as its type, use the raw type or unbounded wildcards (<?> in Java, <*> in Kotlin) instead.")
    }

    private fun pageSchema(resolver: GraphQLResolver<*>) = SchemaParser.newParser()
        .schemaString(
            """
            type Query {
                page: ItemPage!
            }

            type ItemPage {
                content: [Item!]!
                size: Int!
            }

            type Item {
                name: String!
            }
            """)
        .resolvers(QueryResolver3(), resolver)
        .build()
        .makeExecutableSchema()

    class QueryResolver3 : GraphQLQueryResolver {
        fun getPage(): Page<Item> = Page(listOf(Item("item")))
    }

    interface Countable {
        fun count(): Int
    }

    class Page<T>(val content: List<T>) : Countable {
        override fun count(): Int = content.size
    }

    class Item(val name: String)

    class PageResolver : GraphQLResolver<Page<*>> {
        fun getSize(page: Page<*>): Int = page.content.size
    }

    class CountableResolver : GraphQLResolver<Countable> {
        fun getSize(countable: Countable): Int = countable.count()
    }

    class ItemPageSourceResolver : GraphQLResolver<Page<*>> {
        fun getSize(page: Page<Item>): Int = page.content.size
    }

    class ItemPageResolver : GraphQLResolver<Page<Item>> {
        fun getSize(page: Page<Item>): Int = page.content.size
    }
}
