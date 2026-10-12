package graphql.kickstart.tools

import graphql.ExceptionWhileDataFetching
import graphql.GraphQL
import graphql.kickstart.tools.resolver.FieldResolverError
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.delay
import org.aopalliance.intercept.MethodInterceptor
import org.junit.Assert.assertThrows
import org.junit.Test
import org.springframework.aop.framework.ProxyFactory
import java.lang.reflect.Proxy

class ProxiedResolverTest {

    private val invocations = mutableListOf<String>()

    // stands in for the interceptors Spring adds to a proxy, e.g. for @PreAuthorize or @Transactional
    private val recordingInterceptor = MethodInterceptor { invocation ->
        invocations.add("${invocation.method.declaringClass.simpleName}.${invocation.method.name}")
        invocation.proceed()
    }

    private val querySchema = """
        type Query {
            test: String
            greeting(name: String!): String
            suspendTest: String
        }
        """

    @Test
    fun `JDK proxied resolver methods are called through the interface the proxy implements`() {
        val gql = graphQL(querySchema, jdkProxy(Query()))

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                test
            }
            """
        }

        assertEquals(data, mapOf("test" to "ok"))
        assertEquals(invocations, listOf("QueryApi.test"))
    }

    @Test
    fun `JDK proxied resolver methods with arguments are called through the interface the proxy implements`() {
        val gql = graphQL(querySchema, jdkProxy(Query()))

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                greeting(name: "Rex")
            }
            """
        }

        assertEquals(data, mapOf("greeting" to "Hello, Rex, from greeting"))
        assertEquals(invocations, listOf("QueryApi.greeting"))
    }

    @Test
    fun `JDK proxied resolver suspend functions are called through the interface the proxy implements`() {
        val gql = graphQL(querySchema, jdkProxy(Query()))

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                suspendTest
            }
            """
        }

        assertEquals(data, mapOf("suspendTest" to "ok"))
        assertEquals(invocations, listOf("QueryApi.suspendTest"))
    }

    @Test
    fun `JDK proxied resolver interceptors can reject the call`() {
        val gql = graphQL(querySchema, jdkProxy(Query()) { throw IllegalStateException("Access denied") })

        val result = gql.execute(
            """
            query {
                test
            }
            """)

        assertEquals(result.getData(), mapOf("test" to null))
        assertEquals(result.errors.map { (it as ExceptionWhileDataFetching).exception.message }, listOf("Access denied"))
    }

    interface QueryApi {
        fun test(): String
        fun greeting(name: String, env: DataFetchingEnvironment): String
        suspend fun suspendTest(): String
    }

    class Query : GraphQLQueryResolver, QueryApi {
        override fun test() = "ok"
        override fun greeting(name: String, env: DataFetchingEnvironment) = "Hello, $name, from ${env.field.name}"
        override suspend fun suspendTest(): String {
            // suspends, so the result reaches graphql-java through the continuation instead of the proxy's return value
            delay(1)
            return "ok"
        }
    }

    private val petSchema = """
        type Query {
            pet: Pet
        }

        type Pet {
            name: String
            greeting: String
        }
        """

    @Test
    fun `JDK proxied field resolver methods are called through the interface the proxy implements`() {
        val gql = graphQL(petSchema, PetQuery(), jdkProxy(PetResolver()))

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                pet {
                    name
                    greeting
                }
            }
            """
        }

        assertEquals(data, mapOf("pet" to mapOf("name" to "Rex", "greeting" to "Hello, Rex")))
        assertEquals(invocations, listOf("GreetingApi.greeting"))
    }

    @Test
    fun `JDK proxied supertype resolver methods are called through the interface the proxy implements`() {
        val gql = graphQL(petSchema, PetQuery(), jdkProxy(NamedResolver()))

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                pet {
                    greeting
                }
            }
            """
        }

        assertEquals(data, mapOf("pet" to mapOf("greeting" to "Hello, Rex")))
        assertEquals(invocations, listOf("NamedGreetingApi.greeting"))
    }

    @Test
    fun `parser ignores JDK proxied supertype resolver methods when a more specific resolver takes precedence`() {
        val gql = graphQL(petSchema, PetQuery(), PetResolver(), jdkProxy(ClassOnlyNamedResolver()))

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                pet {
                    greeting
                }
            }
            """
        }

        assertEquals(data, mapOf("pet" to mapOf("greeting" to "Hello, Rex")))
    }

    interface Named {
        val name: String
    }

    class Pet(override val name: String) : Named

    class PetQuery : GraphQLQueryResolver {
        fun pet() = Pet("Rex")
    }

    interface GreetingApi {
        fun greeting(pet: Pet): String
    }

    class PetResolver : GraphQLResolver<Pet>, GreetingApi {
        override fun greeting(pet: Pet) = "Hello, ${pet.name}"
    }

    interface NamedGreetingApi {
        fun greeting(named: Named): String
    }

    class NamedResolver : GraphQLResolver<Named>, NamedGreetingApi {
        override fun greeting(named: Named) = "Hello, ${named.name}"
    }

    class ClassOnlyNamedResolver : GraphQLResolver<Named> {
        fun greeting(named: Named) = "Hi, ${named.name}"
    }

    @Test
    fun `JDK proxied resolver methods implementing a generic interface are called through the interface the proxy implements`() {
        val gql = graphQL(
            """
            type Query {
                echo(value: String!): String
                increment(value: Int!): Int
            }
            """,
            jdkProxy(GenericQuery()))

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                echo(value: "ok")
                increment(value: 1)
            }
            """
        }

        assertEquals(data, mapOf("echo" to "ok", "increment" to 2))
        assertEquals(invocations, listOf("EchoApi.echo", "IncrementApi.increment"))
    }

    interface EchoApi<T> {
        fun echo(value: T): T
    }

    interface IncrementApi<T> {
        fun increment(value: T): T
    }

    // compiled to echo(String) and increment(int), which the interfaces' echo(Object) and increment(Object) are bridged to
    class GenericQuery : GraphQLQueryResolver, EchoApi<String>, IncrementApi<Int> {
        override fun echo(value: String) = value
        override fun increment(value: Int) = value + 1
    }

    @Test
    fun `JDK proxied field resolver methods inherited from a generic superclass are called through the interface the proxy implements`() {
        val gql = graphQL(petSchema, PetQuery(), jdkProxy(InheritedPetResolver()))

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                pet {
                    greeting
                }
            }
            """
        }

        assertEquals(data, mapOf("pet" to mapOf("greeting" to "Hello, Rex")))
        assertEquals(invocations, listOf("GenericGreetingApi.greeting"))
    }

    interface GenericGreetingApi<T> {
        fun greeting(pet: T): String
    }

    abstract class GenericGreetingResolver<T : Named> : GraphQLResolver<T>, GenericGreetingApi<T> {
        override fun greeting(pet: T) = "Hello, ${pet.name}"
    }

    // inherits the erased greeting(Named)
    class InheritedPetResolver : GenericGreetingResolver<Pet>()

    @Test
    fun `JDK proxied field resolver methods inherited from a generic superclass are called through a non-generic interface the proxy implements`() {
        val gql = graphQL(petSchema, PetQuery(), jdkProxy(MixedPetResolver()))

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                pet {
                    greeting
                }
            }
            """
        }

        assertEquals(data, mapOf("pet" to mapOf("greeting" to "Hello, Rex")))
        assertEquals(invocations, listOf("GreetingApi.greeting"))
    }

    abstract class PlainGreetingResolver<T : Named> : GraphQLResolver<T> {
        fun greeting(pet: T) = "Hello, ${pet.name}"
    }

    // the inherited greeting(Named) implements GreetingApi.greeting(Pet) through a synthetic bridge, which isn't scanned
    class MixedPetResolver : PlainGreetingResolver<Pet>(), GreetingApi

    @Test
    fun `JDK proxied resolver methods declared on a non-public interface are called through the interface the proxy implements`() {
        val gql = graphQL(
            """
            type Query {
                test: String
            }
            """,
            jdkProxy(PrivateInterfaceQuery()))

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                test
            }
            """
        }

        assertEquals(data, mapOf("test" to "ok"))
        assertEquals(invocations, listOf("PrivateQueryApi.test"))
    }

    private interface PrivateQueryApi {
        fun test(): String
    }

    private class PrivateInterfaceQuery : GraphQLQueryResolver, PrivateQueryApi {
        override fun test() = "ok"
    }

    @Test
    fun `JDK proxied resolver static methods are called directly`() {
        val gql = graphQL(
            """
            type Query {
                test: String
            }
            """,
            jdkProxy(StaticQuery()))

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                test
            }
            """
        }

        assertEquals(data, mapOf("test" to "static"))
    }

    class StaticQuery : GraphQLQueryResolver {
        companion object {
            @JvmStatic
            fun test() = "static"
        }
    }

    @Test
    fun `parser should throw descriptive exception when a JDK proxied resolver method isn't declared on an interface`() {
        val resolver = jdkProxy(ClassOnlyQuery())

        val error = assertThrows(FieldResolverError::class.java) {
            parse(
                """
                type Query {
                    test: String
                }
                """,
                resolver)
        }

        val queryClass = ClassOnlyQuery::class.java.name
        assertEquals(error.message, "Resolver method '$queryClass.test' for field 'test' as defined in schema <unknown>:3 can't be called on " +
            "${resolver.javaClass.name}, which is not an instance of $queryClass. This happens with JDK dynamic proxies, which only implement " +
            "interfaces. Declare the method on an interface the resolver implements, or use class-based proxies (e.g. proxyTargetClass = true in Spring).")
    }

    class ClassOnlyQuery : GraphQLQueryResolver {
        fun test() = "ok"
    }

    @Test
    fun `parser should throw descriptive exception when a JDK proxied resolver method is only declared on an interface the proxy doesn't implement`() {
        // the target implements QueryApi, but the proxy only exposes the interfaces it's given
        val resolver = ProxyFactory().apply {
            setTarget(Query())
            setInterfaces(GraphQLQueryResolver::class.java)
        }.proxy as GraphQLQueryResolver

        val error = assertThrows(FieldResolverError::class.java) {
            parse(
                """
                type Query {
                    test: String
                }
                """,
                resolver)
        }

        assertEquals(error.message?.substringBefore(" as defined"), "Resolver method '${Query::class.java.name}.test' for field 'test'")
    }

    @Test
    fun `parser should throw descriptive exception when a JDK proxied resolver method is only declared as a static interface method`() {
        val error = assertThrows(FieldResolverError::class.java) {
            parse(
                """
                type Query {
                    test: String
                }
                """,
                jdkProxy(StaticInterfaceQuery()))
        }

        assertEquals(error.message?.substringBefore(" as defined"), "Resolver method '${StaticInterfaceQuery::class.java.name}.test' for field 'test'")
    }

    interface StaticApi {
        companion object {
            @JvmStatic
            fun test() = "static"
        }
    }

    // calling the static StaticApi.test() would skip both the proxy and the resolver's own test()
    class StaticInterfaceQuery : GraphQLQueryResolver, StaticApi {
        fun test() = "ok"
    }

    @Test
    fun `class based proxied resolver methods are called through the proxy`() {
        val resolver = ProxyFactory(OpenQuery()).apply {
            isProxyTargetClass = true
            addAdvice(recordingInterceptor)
        }.proxy as GraphQLQueryResolver

        val gql = graphQL(
            """
            type Query {
                test: String
            }
            """,
            resolver)

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                test
            }
            """
        }

        assertEquals(data, mapOf("test" to "ok"))
        assertEquals(invocations, listOf("OpenQuery.test"))
    }

    // CGLIB can only intercept methods it can override
    open class OpenQuery : GraphQLQueryResolver {
        open fun test() = "ok"
    }

    private fun jdkProxy(resolver: GraphQLResolver<*>, interceptor: MethodInterceptor = recordingInterceptor) =
        (ProxyFactory(resolver).apply { addAdvice(interceptor) }.proxy as GraphQLResolver<*>)
            .also { assert(Proxy.isProxyClass(it.javaClass)) }

    private fun graphQL(schema: String, vararg resolvers: GraphQLResolver<*>) =
        GraphQL.newGraphQL(parse(schema, *resolvers).makeExecutableSchema()).build()

    private fun parse(schema: String, vararg resolvers: GraphQLResolver<*>) = SchemaParser.newParser()
        .schemaString(schema)
        .resolvers(*resolvers)
        .build()
}
