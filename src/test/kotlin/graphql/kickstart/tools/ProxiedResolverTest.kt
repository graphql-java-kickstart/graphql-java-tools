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

    @Test
    fun `JDK proxied resolver methods are called through the interface the proxy implements`() {
        val gql = graphQL(
            """
            type Query {
                test: String
                greeting(name: String!): String
                suspendTest: String
                increment(value: Int!): Int
                staticTest: String
            }
            """,
            jdkProxy(Query()))

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                test
                greeting(name: "Rex")
                suspendTest
                increment(value: 1)
                staticTest
            }
            """
        }

        assertEquals(data, mapOf("test" to "ok", "greeting" to "Hello, Rex, from greeting", "suspendTest" to "ok", "increment" to 2, "staticTest" to "static"))
        // static methods are called directly, so they skip the interceptors
        assertEquals(invocations, listOf("QueryApi.test", "QueryApi.greeting", "QueryApi.suspendTest", "QueryApi.increment"))
    }

    @Test
    fun `JDK proxied resolver interceptors can reject the call`() {
        val gql = graphQL("type Query { test: String }", jdkProxy(Query()) { throw IllegalStateException("Access denied") })

        val result = gql.execute("query { test }")

        assertEquals(result.getData(), mapOf("test" to null))
        assertEquals(result.errors.map { (it as ExceptionWhileDataFetching).exception.message }, listOf("Access denied"))
    }

    // not public, so its methods have to be made accessible
    private interface QueryApi<T> {
        fun test(): String
        fun greeting(name: String, env: DataFetchingEnvironment): String
        suspend fun suspendTest(): String
        fun increment(value: T): T
    }

    // increment(Int) is compiled to increment(int), which the interface's increment(Object) is bridged to
    private class Query : GraphQLQueryResolver, QueryApi<Int> {
        override fun test() = "ok"
        override fun greeting(name: String, env: DataFetchingEnvironment) = "Hello, $name, from ${env.field.name}"
        override suspend fun suspendTest(): String {
            // suspends, so the result reaches graphql-java through the continuation instead of the proxy's return value
            delay(1)
            return "ok"
        }
        override fun increment(value: Int) = value + 1

        companion object {
            @JvmStatic
            fun staticTest() = "static"
        }
    }

    @Test
    fun `JDK proxied field resolver methods are called through the interface the proxy implements`() {
        val dog = mapOf("name" to "Rex", "greeting" to "Hello, Rex")

        // resolvers for the data class and for its supertype
        assertEquals(queryDog(jdkProxy(LabradorResolver())), dog)
        assertEquals(queryDog(jdkProxy(DogResolver())), dog)
        // greeting(Dog) is inherited from a class that doesn't implement the interface
        assertEquals(queryDog(jdkProxy(BridgedLabradorResolver())), dog)
        // LabradorResolver takes precedence, so the supertype resolver's class-only greeting isn't checked
        assertEquals(queryDog(jdkProxy(LabradorResolver()), jdkProxy(ClassOnlyDogResolver())), dog)

        assertEquals(invocations, listOf("GreetingApi.greeting", "GreetingApi.greeting", "LabradorGreetingApi.greeting", "GreetingApi.greeting"))
    }

    class DogQuery : GraphQLQueryResolver {
        fun dog() = LabradorRetriever("Rex", "black", 3, 1f)
    }

    interface GreetingApi<T> {
        fun greeting(dog: T): String
    }

    // greeting(T) is erased to greeting(Dog), which the interface's greeting(Object) is bridged to
    abstract class GreetingResolver<T : Dog> : GraphQLResolver<T>, GreetingApi<T> {
        override fun greeting(dog: T) = "Hello, ${dog.name}"
    }

    class LabradorResolver : GreetingResolver<LabradorRetriever>()

    class DogResolver : GreetingResolver<Dog>()

    abstract class ClassOnlyGreetingResolver<T : Dog> : GraphQLResolver<T> {
        fun greeting(dog: T) = "Hello, ${dog.name}"
    }

    interface LabradorGreetingApi {
        fun greeting(dog: LabradorRetriever): String
    }

    // the inherited greeting(Dog) implements LabradorGreetingApi.greeting(LabradorRetriever) through a synthetic bridge, which isn't scanned
    class BridgedLabradorResolver : ClassOnlyGreetingResolver<LabradorRetriever>(), LabradorGreetingApi

    class ClassOnlyDogResolver : ClassOnlyGreetingResolver<Dog>()

    @Test
    fun `parser should throw descriptive exception when a JDK proxied resolver method can't be called through the proxy`() {
        val resolver = jdkProxy(ClassOnlyQuery())
        val queryClass = ClassOnlyQuery::class.java.name
        assertEquals(buildError(resolver), "Resolver method '$queryClass.test' for field 'test' as defined in schema <unknown>:1 can't be called on " +
            "${resolver.javaClass.name}, which is not an instance of $queryClass. This happens with JDK dynamic proxies, which only implement " +
            "interfaces. Declare the method on an interface the resolver implements, or use class-based proxies (e.g. proxyTargetClass = true in Spring).")

        // the target implements QueryApi, but the proxy only implements the interfaces it's given
        val hidingProxy = ProxyFactory().apply {
            setTarget(Query())
            setInterfaces(GraphQLQueryResolver::class.java)
        }.proxy as GraphQLQueryResolver
        assertEquals(buildError(hidingProxy)?.substringBefore(" as defined"), "Resolver method '${Query::class.java.name}.test' for field 'test'")

        // test(Integer) is scanned, but the proxy would call test(int), which implements OverloadApi<Int>.test(T)
        assertEquals(buildError(jdkProxy(OverloadQuery()), "type Query { test(value: Int!): String }")?.substringBefore(" as defined"),
            "Resolver method '${OverloadQuery::class.java.name}.test' for field 'test'")
    }

    interface StaticApi {
        companion object {
            @JvmStatic
            fun test() = "static"
        }
    }

    // calling the static StaticApi.test() instead would skip both the proxy and the resolver's own test()
    class ClassOnlyQuery : GraphQLQueryResolver, StaticApi {
        fun test() = "ok"
    }

    interface OverloadApi<T> {
        fun test(value: T): String
    }

    abstract class OverloadBase : OverloadApi<Int> {
        override fun test(value: Int) = "override"
    }

    class OverloadQuery : OverloadBase(), GraphQLQueryResolver {
        fun test(value: Int?) = "overload"
    }

    @Test
    fun `class based proxied resolver methods are called through the proxy`() {
        val resolver = ProxyFactory(OpenQuery()).apply {
            isProxyTargetClass = true
            addAdvice(recordingInterceptor)
        }.proxy as GraphQLQueryResolver

        val data = assertNoGraphQlErrors(graphQL("type Query { test: String }", resolver)) { "query { test }" }

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

    private fun queryDog(vararg resolvers: GraphQLResolver<*>) =
        assertNoGraphQlErrors(graphQL("type Query { dog: Dog } type Dog { name: String greeting: String }", DogQuery(), *resolvers)) {
            "query { dog { name greeting } }"
        }["dog"]

    private fun buildError(resolver: GraphQLResolver<*>, schema: String = "type Query { test: String }") =
        assertThrows(FieldResolverError::class.java) { parse(schema, resolver) }.message

    private fun graphQL(schema: String, vararg resolvers: GraphQLResolver<*>) =
        GraphQL.newGraphQL(parse(schema, *resolvers).makeExecutableSchema()).build()

    private fun parse(schema: String, vararg resolvers: GraphQLResolver<*>) = SchemaParser.newParser()
        .schemaString(schema)
        .resolvers(*resolvers)
        .build()
}
