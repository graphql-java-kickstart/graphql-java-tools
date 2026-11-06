package graphql.kickstart.tools

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.GraphQLContext
import graphql.execution.DataFetcherResult
import graphql.kickstart.tools.SchemaParserOptions.GenericWrapper
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.publish
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test
import org.reactivestreams.Publisher
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.CompletableFuture.supplyAsync
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

class ResolverCompatibilityTest {

    @Test
    fun `@JvmField and typed Map fields return what a method returns, and untyped Map fields hand graphql-java the value as it is`() {
        val wrong = Kind.entries.flatMap { kind ->
            shapes.mapNotNull { shape ->
                val got = holderField(kind, shape)
                val expected = if (kind == Kind.UNTYPED_MAP) rawValueResults.getValue(shape.name) else shape.expected(kind)
                "$kind ${shape.name}\n  expected: $expected\n  got:      $got".takeUnless { got == expected }
            }
        }
        if (wrong.isNotEmpty()) {
            fail(wrong.joinToString("\n"))
        }
    }

    @Test
    fun `arguments follow the source parameter in declaration order`() {
        val result = execute(
            """
            type Query { foo: Foo }
            type Foo { name: String joined(first: String!, second: String!, count: Int!): String }
            """,
            "{ foo { joined(first: \"x\", second: \"y\", count: 3) } }",
            object : GraphQLQueryResolver {
                fun foo() = Foo("a")
            },
            object : GraphQLResolver<Foo> {
                fun joined(foo: Foo, first: String, second: String, count: Int, env: DataFetchingEnvironment) =
                    "${foo.name} $first $second $count ${env.field.name}"
            }
        )

        assertEquals(result, "{foo={joined=a x y 3 joined}}")
    }

    @Test
    fun `trailing parameters bind the GraphQLContext, the context class and the environment, repeated and in any order`() {
        val schema = "type Query { extras(first: String!): String }"
        val options = SchemaParserOptions.newOptions().contextClass(AppContext::class).build()

        assertEquals(
            execute(schema, "{ extras(first: \"x\") }", ContextResolver(), options = options, context = mapOf(AppContext::class.java to AppContext("ann"), "key" to "k")),
            "{extras=x extras ann k true}"
        )
        assertEquals(
            execute(schema, "{ extras(first: \"x\") }", ContextResolver(), options = options),
            "{extras=null}\nerror: Exception while fetching data (/extras) : Resolver method 'ContextResolver.extras' has a parameter of the context class " +
                "'AppContext', but the GraphQLContext has no value for it. Put it in the GraphQLContext under the class key when building the " +
                "ExecutionInput, for example graphQLContext(mapOf(AppContext::class.java to context))."
        )
    }

    @Test
    fun `a context class of DataFetchingEnvironment is looked up in the GraphQLContext`() {
        val result = execute(
            "type Query { environment: String }",
            "{ environment }",
            EnvironmentResolver(),
            options = SchemaParserOptions.newOptions().contextClass(DataFetchingEnvironment::class).build()
        )

        assertEquals(
            result,
            "{environment=null}\nerror: Exception while fetching data (/environment) : Resolver method 'EnvironmentResolver.environment' has a parameter " +
                "of the context class 'graphql.schema.DataFetchingEnvironment', but the GraphQLContext has no value for it. Put it in the GraphQLContext " +
                "under the class key when building the ExecutionInput, for example graphQLContext(mapOf(DataFetchingEnvironment::class.java to context))."
        )
    }

    @Test
    fun `omitted arguments reach primitive parameters as their default, and a type variable parameter gets a value of its resolved type argument`() {
        val result = execute(
            "type Query { primitives(i: Int, b: Boolean, d: Float, boxed: Int): String echo(value: Int!): String }",
            "{ primitives echo(value: 3) }",
            PrimitivesResolver()
        )

        assertEquals(result, "{primitives=0 false 0.0 null, echo=3 Long}")
    }

    @Test
    fun `Optional arguments are empty when null, and null when omitted with omission detection`() {
        val schema = "type Query { raw(value: String): String typed(value: String): String }"
        val query = "{ givenRaw: raw(value: \"x\") nullRaw: raw(value: null) omittedRaw: raw given: typed(value: \"x\") null: typed(value: null) omitted: typed }"
        val typed = object : GraphQLQueryResolver {
            fun typed(value: Optional<String>?) = value.toString()
        }
        val detecting = SchemaParserOptions.newOptions().inputArgumentOptionalDetectOmission(true).build()

        assertEquals(
            execute(schema, query, RawGenericFixtures.RawOptionalResolver(), typed),
            "{givenRaw=Optional[x], nullRaw=Optional.empty, omittedRaw=Optional.empty, given=Optional[x], null=Optional.empty, omitted=Optional.empty}"
        )
        assertEquals(
            execute(schema, query, RawGenericFixtures.RawOptionalResolver(), typed, options = detecting),
            "{givenRaw=Optional[x], nullRaw=Optional.empty, omittedRaw=null, given=Optional[x], null=Optional.empty, omitted=null}"
        )
    }

    @Test
    fun `lists of concrete scalars skip conversion only for a List parameter`() {
        val result = execute(
            "type Query { numbers(list: [Int!]!, collection: [Int!]!, set: [Int!]!): String }",
            "{ numbers(list: [1], collection: [2], set: [3]) }",
            object : GraphQLQueryResolver {
                fun numbers(list: List<Long>, collection: Collection<Long>, set: Set<Long>) =
                    listOf<Collection<*>>(list, collection, set).joinToString(" ") { it.first()!!.javaClass.simpleName }
            }
        )

        assertEquals(result, "{numbers=Integer Long Long}")
    }

    @Test
    fun `a source of another class fails the field`() {
        val result = execute(
            "type Query { holder: Holder } type Holder { name: String tag: String extra: String }",
            "{ holder { name tag extra } }",
            object : GraphQLQueryResolver {
                fun holder(): Any = Impostor()
            },
            object : GraphQLResolver<Holder> {
                fun extra(holder: Holder) = holder.name
            },
            dictionary = listOf(Holder::class.java)
        )

        assertEquals(
            result,
            """
            {holder={name=null, tag=null, extra=null}}
            error: Exception while fetching data (/holder/name) : Expected source object to be an instance of 'Holder' but instead got 'Impostor'
            error: Exception while fetching data (/holder/tag) : Expected source object to be an instance of 'Holder' but instead got 'Impostor'
            error: Exception while fetching data (/holder/extra) : Source type (Impostor) is not expected type (Holder)!
            """.trimIndent()
        )
    }

    @Test
    fun `the outermost wrapper is picked by the value's class and a nested one by the declared type`() {
        val result = execute(
            "type Query { top: String nested: [String] }",
            "{ top nested }",
            object : GraphQLQueryResolver {
                fun top(): Box<String> = SpecialBox("a")
                fun nested(): List<Box<String>> = listOf(SpecialBox("b"))
            },
            options = SchemaParserOptions.newOptions().genericWrappers(boxWrapper, specialBoxWrapper).build()
        )

        assertEquals(result, "{top=a special, nested=[b]}")
    }

    @Test
    fun `a value of two unrelated wrapper types gets the transformer registered last, whatever the declared type`() {
        val result = execute(
            "type Query { left: String right: String }",
            "{ left right }",
            object : GraphQLQueryResolver {
                fun left(): Left<String> = Both("l", "r")
                fun right(): Right<String> = Both("l", "r")
            },
            options = SchemaParserOptions.newOptions().genericWrappers(leftWrapper, rightWrapper).build()
        )

        assertEquals(result, "{left=r, right=r}")
    }

    @Test
    fun `suspend functions await nested futures, and fail on a future at the top`() {
        val result = execute("type Query { futures: [String] future: String }", "{ futures future }", SuspendResolver())

        assertEquals(
            result,
            "{futures=[futures, b], future=null}\nerror: Exception while fetching data (/future) : Suspend function 'SuspendResolver.future' resolved to " +
                "a CompletableFuture, which graphql-java can't wait on. Return its value instead."
        )
    }

    @Test
    fun `subscription events of a method or a field reach graphql-java as they are, so a future or wrapped event prints as a string`() {
        val schema = SchemaParser.newParser()
            .schemaString("type Query { unused: String } type Subscription { events: String futureEvents: String boxEvents: String }")
            .resolvers(
                object : GraphQLQueryResolver {
                    fun unused(): String? = null
                },
                object : GraphQLSubscriptionResolver {
                    fun events(): Publisher<String> = publish {
                        send("a")
                        send("b")
                    }

                    fun futureEvents(): Publisher<CompletableFuture<String>> = publish {
                        send(completedFuture("a"))
                    }
                },
                SubscriptionFields())
            .options(SchemaParserOptions.newOptions().genericWrappers(boxWrapper).build())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        fun events(query: String) = runBlocking { gql.execute(query).getData<Publisher<ExecutionResult>>()!!.asFlow().toList() }.map { it.render() }

        assertEquals(events("subscription { events }"), listOf("{events=a}", "{events=b}"))
        assertEquals(events("subscription { futureEvents }"), listOf("{futureEvents=CompletableFuture@...[Completed normally]}"))
        assertEquals(events("subscription { boxEvents }"), listOf("{boxEvents=Box@...}"))
    }

    private fun execute(
        schema: String,
        query: String,
        vararg resolvers: GraphQLResolver<*>,
        options: SchemaParserOptions = SchemaParserOptions.newOptions().build(),
        dictionary: List<Class<*>> = listOf(),
        context: Map<Any, Any> = mapOf()
    ): String = GraphQL.newGraphQL(
        SchemaParser.newParser().schemaString(schema).resolvers(*resolvers).options(options).dictionary(dictionary).build().makeExecutableSchema()
    ).build().execute(ExecutionInput.newExecutionInput(query).graphQLContext(context)).render()

    private fun holderField(kind: Kind, shape: Shape): String {
        val holder = kind.holder(shape)
        val parser = SchemaParser.newParser()
            .schemaString("type Query { holder: Holder } type Holder { ${shape.name}: ${shape.type} }" + if (shape.selection.isEmpty()) "" else " type Foo { name: String }")
            .resolvers(object : GraphQLQueryResolver {
                fun holder(): Any = holder
            })
            .dictionary("Holder", holder.javaClass)
            .options(SchemaParserOptions.newOptions().genericWrappers(boxWrapper).build())
        if (kind == Kind.UNTYPED_MAP && shape.selection.isNotEmpty()) {
            parser.dictionary(Foo::class)
        }
        val schema = try {
            parser.build().makeExecutableSchema()
        } catch (e: RuntimeException) {
            return "rejected: ${e.message!!.lines().first().substringAfterLast("): ")}"
        }
        val result = GraphQL.newGraphQL(schema).build().execute("{ holder { ${shape.name}${shape.selection} } }")
        val value = result.getData<Map<String, Map<String, Any?>?>>()!!["holder"]?.get(shape.name)
        return (listOf("$value") + result.errors.map { "error: ${it.message}" }).joinToString("\n").normalized()
    }

    private fun ExecutionResult.render() = (listOf("${getData<Any>()}") + errors.map { "error: ${it.message}" }).joinToString("\n").normalized()

    private fun String.normalized() = replace(Regex("@\\p{XDigit}+"), "@...")
        .replace("${ResolverCompatibilityTest::class.java.name}$", "")
        .replace("java.util.concurrent.", "")

    enum class Kind(val resolverKind: String, val holder: (Shape) -> Any) {
        METHOD("method", { MethodHolder() }),
        JVM_FIELD("field", { JvmFieldHolder() }),
        TYPED_MAP("map value", { shape -> shape.typedMap(JvmFieldHolder()) }),
        UNTYPED_MAP("map value", { shape -> hashMapOf(shape.name to shape.value()) })
    }

    class Shape(val name: String, val type: String, private val expected: String, val typedMap: (JvmFieldHolder) -> Map<String, *>) {
        val selection = if (type.contains("Foo")) " { name }" else ""

        fun value(): Any? = JvmFieldHolder::class.java.getField(name).get(JvmFieldHolder())

        fun expected(kind: Kind) = expected.replace("HOLDER", kind.holder(this).javaClass.simpleName).replace("KIND", kind.resolverKind)
    }

    class Foo(val name: String)

    class JvmFieldHolder {
        @JvmField val future: CompletableFuture<Foo> = completedFuture(Foo("a"))
        @JvmField val futureOfListOfFutures: CompletableFuture<List<CompletableFuture<Foo>>> = completedFuture(listOf(completedFuture(Foo("a")), completedFuture(Foo("b"))))
        @JvmField val optional: Optional<Foo> = Optional.of(Foo("a"))
        @JvmField val result: DataFetcherResult<Foo> = DataFetcherResult.newResult<Foo>().data(Foo("a")).build()
        @JvmField val plainFuture: Future<Foo> = FutureTask { Foo("a") }.apply { run() }
        @JvmField val box: Box<Foo> = Box(Foo("a"))
        @JvmField val optionalResult: Optional<DataFetcherResult<Foo>> = Optional.of(DataFetcherResult.newResult<Foo>().data(Foo("a")).build())
        @JvmField val listOfFutures: List<CompletableFuture<Foo>> = listOf(completedFuture(Foo("a")), completedFuture(Foo("b")))
        @JvmField val listOfStringFutures: List<CompletableFuture<String>> = listOf(completedFuture("a"), completedFuture("b"))
        @JvmField val boxOfString: Box<String> = Box("a")
        @JvmField val listOfOptionals: List<Optional<String>> = listOf(Optional.of("a"), Optional.empty())
        @JvmField val futureOfAny: CompletableFuture<Any> = completedFuture("a")
    }

    class MethodHolder {
        private val values = JvmFieldHolder()

        fun future() = values.future
        fun futureOfListOfFutures() = values.futureOfListOfFutures
        fun optional() = values.optional
        fun result() = values.result
        fun plainFuture() = values.plainFuture
        fun box() = values.box
        fun optionalResult() = values.optionalResult
        fun listOfFutures() = values.listOfFutures
        fun listOfStringFutures() = values.listOfStringFutures
        fun boxOfString() = values.boxOfString
        fun listOfOptionals() = values.listOfOptionals
        fun futureOfAny() = values.futureOfAny
    }

    class FutureMap : HashMap<String, CompletableFuture<Foo>>()
    class FutureOfListOfFuturesMap : HashMap<String, CompletableFuture<List<CompletableFuture<Foo>>>>()
    class OptionalMap : HashMap<String, Optional<Foo>>()
    class ResultMap : HashMap<String, DataFetcherResult<Foo>>()
    class PlainFutureMap : HashMap<String, Future<Foo>>()
    class BoxMap : HashMap<String, Box<Foo>>()
    class OptionalResultMap : HashMap<String, Optional<DataFetcherResult<Foo>>>()
    class ListOfFuturesMap : HashMap<String, List<CompletableFuture<Foo>>>()
    class ListOfStringFuturesMap : HashMap<String, List<CompletableFuture<String>>>()
    class BoxOfStringMap : HashMap<String, Box<String>>()
    class ListOfOptionalsMap : HashMap<String, List<Optional<String>>>()
    class FutureOfAnyMap : HashMap<String, CompletableFuture<Any>>()

    open class Box<T>(val content: T)

    class SpecialBox<T>(content: T) : Box<T>(content)

    interface Left<T> {
        val left: T
    }

    interface Right<T> {
        val right: T
    }

    class Both<T>(override val left: T, override val right: T) : Left<T>, Right<T>

    class AppContext(val user: String)

    class ContextResolver : GraphQLQueryResolver {
        fun extras(first: String, env: DataFetchingEnvironment, context: AppContext, graphQLContext: GraphQLContext, again: DataFetchingEnvironment) =
            "$first ${env.field.name} ${context.user} ${graphQLContext.get<String>("key")} ${again === env}"
    }

    class EnvironmentResolver : GraphQLQueryResolver {
        fun environment(env: DataFetchingEnvironment): String = env.field.name
    }

    abstract class Echo<T> {
        fun echo(value: T) = "$value ${value!!::class.java.simpleName}"
    }

    class PrimitivesResolver : Echo<Long>(), GraphQLQueryResolver {
        fun primitives(i: Int, b: Boolean, d: Double, boxed: Int?) = "$i $b $d $boxed"
    }

    class SuspendResolver : GraphQLQueryResolver {
        suspend fun futures(env: DataFetchingEnvironment): List<CompletableFuture<String>> = listOf(completedFuture(env.field.name), supplyAsync { "b" })
        suspend fun future(): CompletableFuture<String> = completedFuture("a")
    }

    class SubscriptionFields : GraphQLSubscriptionResolver {
        @JvmField val boxEvents: Publisher<Box<String>> = publish { send(Box("a")) }
    }

    class Holder(val name: String) {
        @JvmField val tag = "t"
    }

    class Impostor

    private companion object {
        val boxWrapper = GenericWrapper.withTransformer(Box::class, 0, { box -> box.content })
        val specialBoxWrapper = GenericWrapper.withTransformer(SpecialBox::class, 0, { box -> "${box.content} special" })
        val leftWrapper = GenericWrapper.withTransformer(Left::class, 0, { wrapper -> wrapper.left })
        val rightWrapper = GenericWrapper.withTransformer(Right::class, 0, { wrapper -> wrapper.right })

        val shapes = listOf(
            Shape("future", "Foo", "{name=a}") { FutureMap().apply { put("future", it.future) } },
            Shape("futureOfListOfFutures", "[Foo]", "[{name=a}, {name=b}]") { FutureOfListOfFuturesMap().apply { put("futureOfListOfFutures", it.futureOfListOfFutures) } },
            Shape("optional", "Foo", "{name=a}") { OptionalMap().apply { put("optional", it.optional) } },
            Shape("result", "Foo", "{name=a}") { ResultMap().apply { put("result", it.result) } },
            Shape(
                "plainFuture", "Foo",
                "null\nerror: Exception while fetching data (/holder/plainFuture) : Resolver KIND 'HOLDER.plainFuture' resolved to a FutureTask, " +
                    "which graphql-java can't wait on. Return a CompletionStage, such as a CompletableFuture, from the KIND or its generic wrapper transformer instead."
            ) { PlainFutureMap().apply { put("plainFuture", it.plainFuture) } },
            Shape("box", "Foo", "{name=a}") { BoxMap().apply { put("box", it.box) } },
            Shape("optionalResult", "Foo", "{name=a}") { OptionalResultMap().apply { put("optionalResult", it.optionalResult) } },
            Shape("listOfFutures", "[Foo]", "[{name=a}, {name=b}]") { ListOfFuturesMap().apply { put("listOfFutures", it.listOfFutures) } },
            Shape("listOfStringFutures", "[String]", "[a, b]") { ListOfStringFuturesMap().apply { put("listOfStringFutures", it.listOfStringFutures) } },
            Shape("boxOfString", "String", "a") { BoxOfStringMap().apply { put("boxOfString", it.boxOfString) } },
            Shape("listOfOptionals", "[String]", "rejected: java.util.Optional can only be used at the top level of a return type") { ListOfOptionalsMap().apply { put("listOfOptionals", it.listOfOptionals) } },
            Shape("futureOfAny", "String", "a") { FutureOfAnyMap().apply { put("futureOfAny", it.futureOfAny) } }
        )

        private fun notFoo(path: String, got: String) =
            "error: Exception while fetching data (/holder/$path/name) : Expected source object to be an instance of 'Foo' but instead got '$got'"

        val rawValueResults = mapOf(
            "future" to "{name=a}",
            "futureOfListOfFutures" to "[{name=null}, {name=null}]\n${notFoo("futureOfListOfFutures[0]", "CompletableFuture")}\n${notFoo("futureOfListOfFutures[1]", "CompletableFuture")}",
            "optional" to "{name=a}",
            "result" to "{name=a}",
            "plainFuture" to "{name=null}\n${notFoo("plainFuture", "FutureTask")}",
            "box" to "{name=null}\n${notFoo("box", "Box")}",
            "optionalResult" to "{name=null}\n${notFoo("optionalResult", "graphql.execution.DataFetcherResult")}",
            "listOfFutures" to "[{name=null}, {name=null}]\n${notFoo("listOfFutures[0]", "CompletableFuture")}\n${notFoo("listOfFutures[1]", "CompletableFuture")}",
            "listOfStringFutures" to "[CompletableFuture@...[Completed normally], CompletableFuture@...[Completed normally]]",
            "boxOfString" to "Box@...",
            "listOfOptionals" to "[a, null]",
            "futureOfAny" to "a"
        )
    }
}
