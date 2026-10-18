package graphql.kickstart.tools

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.POJONode
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import graphql.ExceptionWhileDataFetching
import graphql.GraphQL
import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.GraphQLScalarType
import org.junit.Test
import java.time.LocalDate
import java.util.*
import javax.servlet.http.Part

class CustomScalarInputTest {

    private val resolver = QueryResolver()

    @Test
    fun `custom scalars in input objects are passed to the resolver`() {
        val variables = mapOf(
            "event" to mapOf("date" to "2021-03-04", "opaque" to "variable"),
            "file" to MockPart("a.txt", "Hello"),
            "files" to listOf(MockPart("b.txt", "big"), MockPart("c.txt", "world"))
        )
        val data = assertNoGraphQlErrors(graphQL(inputSchema), variables) {
            """
            query(${'$'}event: EventInput!, ${'$'}file: Upload, ${'$'}files: [Upload!]) {
                literal: event(input: { date: "2020-01-02", opaque: "literal" })
                opaqueOnly: event(input: { opaque: "alone" })
                variable: event(input: ${'$'}event)
                file: event(input: { file: ${'$'}file })
                events(inputs: [{ date: "2020-01-02" }, { opaque: "second" }])
                optionalEvent(input: { date: "2020-01-02", opaque: "optional" })
                message(input: { name: "greeting", file: ${'$'}file, files: ${'$'}files })
                dateNode(input: { date: "2020-01-02" })
                dateLabel: label(input: { date: "2020-01-02" })
                opaqueLabel: label(input: { opaque: "secret" })
            }
            """
        }

        assertEquals(data - "opaqueLabel", mapOf(
            "literal" to "2020-01-02 literal",
            "opaqueOnly" to "alone",
            "variable" to "2021-03-04 variable",
            "file" to "a.txt: Hello",
            "events" to listOf("2020-01-02", "second"),
            "optionalEvent" to "2020-01-02 optional",
            "message" to "greeting: Hello big world",
            "dateNode" to "LocalDate 2020-01-02",
            "dateLabel" to "2020-01-02 null"
        ))
        assert((data["opaqueLabel"] as String).startsWith("null ${Opaque::class.java.name}@")) { data["opaqueLabel"].toString() }
    }

    @Test
    fun `custom scalars in Map arguments are passed as coerced values`() {
        val schema = """
            scalar LocalDate
            scalar Money
            scalar UUID

            type Query {
                map(input: MapInput!): Boolean!
                mapList(inputs: [MapInput!]!): Boolean!
                optionalMap(input: OptionalMapInput): Boolean!
                rawMap(input: RawMapInput!): Boolean!
            }

            input MapInput {
                name: String
                date: LocalDate
                price: Money
                id: UUID
                next: MapInput
            }

            input OptionalMapInput {
                price: Money
            }

            input RawMapInput {
                price: Money
            }
            """

        assertNoGraphQlErrors(graphQL(schema)) {
            """
            query {
                map(input: { name: null, date: "2020-01-02", price: "5", id: "4dbd9a3d-4a26-4d10-9bb2-5b2dc4c8a0f0", next: { date: "2021-03-04" } })
                mapList(inputs: [{ price: "6" }, { price: "7" }])
                optionalMap(input: { price: "8" })
                rawMap(input: { price: "9" })
            }
            """
        }

        assertEquals(resolver.received, mapOf(
            "map" to mapOf(
                "name" to null,
                "date" to LocalDate.of(2020, 1, 2),
                "price" to Money(5, "CAD"),
                "id" to UUID.fromString("4dbd9a3d-4a26-4d10-9bb2-5b2dc4c8a0f0"),
                "next" to mapOf("date" to LocalDate.of(2021, 3, 4))
            ),
            "mapList" to listOf(mapOf("price" to Money(6, "CAD")), mapOf("price" to Money(7, "CAD"))),
            "optionalMap" to Optional.of(mapOf("price" to Money(8, "CAD"))),
            "rawMap" to mapOf("price" to Money(9, "CAD"))
        ))

        assertNoGraphQlErrors(graphQL(schema, configuredMapperOptions)) {
            """
            query {
                map(input: { name: null, date: "2020-01-02" })
            }
            """
        }

        assertEquals(resolver.received["map"], mapOf("date" to LocalDate.of(2020, 1, 2)))
    }

    @Test
    fun `custom scalars the ObjectMapper can convert are still converted by it`() {
        val data = assertNoGraphQlErrors(graphQL(inputSchema)) {
            """
            query {
                payment(input: { price: "5", reference: "ref", box: "text" })
                withDate: payment(input: { price: "5", reference: "ref", box: "text", date: "2020-01-02" })
                boxOfDate: payment(input: { box: "date:2021-03-04" })
                afterBoxOfDate: payment(input: { price: "5", reference: "ref", box: "text", date: "2020-01-02" })
                priceNode(input: { price: "5" })
                price(price: "5")
            }
            """
        }
        val configured = assertNoGraphQlErrors(graphQL(inputSchema, configuredMapperOptions), mapOf("file" to MockPart("a.txt", "Hello"))) {
            """
            query(${'$'}file: Upload) {
                event(input: { date: "2020-01-02", file: ${'$'}file })
            }
            """
        }

        assertEquals(data, mapOf(
            "payment" to "MoneyDto(amount=5, currency=CAD) OpaqueDto(value=ref) text",
            "withDate" to "MoneyDto(amount=5, currency=CAD) OpaqueDto(value=ref) 2020-01-02 text",
            "boxOfDate" to "<missing>",
            "afterBoxOfDate" to "MoneyDto(amount=5, currency=CAD) OpaqueDto(value=ref) 2020-01-02 text",
            "priceNode" to """{"price":{"amount":5,"currency":"CAD"}}""",
            "price" to "MoneyDto(amount=5, currency=CAD)"
        ))
        assertEquals(configured, mapOf("event" to "2020-01-03 copy of a.txt: Hello"))
    }

    @Test
    fun `errors converting inputs with custom scalars name the actual problem`() {
        val result = graphQL(inputSchema).execute(
            """
            query {
                message(input: { name: null, date: "2020-01-02" })
            }
            """)

        val exception = (result.errors.single() as ExceptionWhileDataFetching).exception
        assertEquals((exception.cause as MismatchedInputException).path.map { it.fieldName }, listOf("name"))
        assertEquals((exception.suppressed.single().cause as InvalidDefinitionException).type.rawClass, LocalDate::class.java)
    }

    private fun graphQL(schema: String, options: SchemaParserOptions = SchemaParserOptions.defaultOptions()): GraphQL {
        val graphQLSchema = SchemaParser.newParser()
            .schemaString(schema)
            .scalars(localDateScalar, opaqueScalar, boxScalar, moneyScalar, customScalarUUID, uploadScalar)
            .resolvers(resolver)
            .options(options)
            .build()
            .makeExecutableSchema()
        return GraphQL.newGraphQL(graphQLSchema).build()
    }

    private val inputSchema = """
        scalar LocalDate
        scalar Opaque
        scalar Box
        scalar Money
        scalar Upload

        type Query {
            event(input: EventInput!): String!
            events(inputs: [EventInput!]!): [String!]!
            optionalEvent(input: EventInput): String!
            message(input: MessageInput!): String!
            label(input: LabelInput!): String!
            dateNode(input: NodeInput!): String!
            priceNode(input: NodeInput!): String!
            payment(input: PaymentInput!): String!
            price(price: Money!): String!
        }

        input EventInput {
            date: LocalDate
            opaque: Opaque
            file: Upload
        }

        input MessageInput {
            name: String
            date: LocalDate
            file: Upload
            files: [Upload!]
        }

        input LabelInput {
            date: LocalDate
            opaque: Opaque
        }

        input NodeInput {
            date: LocalDate
            price: Money
        }

        input PaymentInput {
            price: Money
            reference: Opaque
            date: LocalDate
            box: Box
        }
        """

    private val configuredMapperOptions = SchemaParserOptions.newOptions()
        .objectMapperConfigurer { mapper, _ ->
            mapper.registerModule(SimpleModule()
                .addSerializer(LocalDate::class.java, ToStringSerializer.instance)
                .addDeserializer(LocalDate::class.java, NextDayDeserializer())
                .addSerializer(Part::class.java, PartSerializer())
                .addDeserializer(Part::class.java, PartDeserializer()))
            mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
        }
        .build()

    class QueryResolver : GraphQLQueryResolver {
        val received = mutableMapOf<String, Any>()

        fun event(input: EventInput): String =
            listOfNotNull(input.date, input.opaque?.value, input.file?.let { "${it.name}: ${it.content()}" }).joinToString(" ")

        fun events(inputs: List<EventInput>): List<String> = inputs.map { event(it) }
        fun optionalEvent(input: Optional<EventInput>): String = input.map { event(it) }.orElse("empty")
        fun message(input: MessageInput): String = "${input.name}: ${(listOfNotNull(input.file) + input.files).joinToString(" ") { it.content() }}"
        fun label(input: LabelInput): String = "${input.date} ${input.opaque}"
        fun dateNode(input: ObjectNode): String = (input["date"] as POJONode).pojo.let { "${it.javaClass.simpleName} $it" }
        fun priceNode(input: ObjectNode): String = input.toString()
        fun payment(input: PaymentInput): String = listOfNotNull(input.price, input.reference, input.date, input.box).joinToString(" ")
        fun price(price: MoneyDto): String = price.toString()

        fun map(input: Map<String, Any?>) = receive("map", input)
        fun mapList(inputs: List<Map<String, Any?>>) = receive("mapList", inputs)
        fun optionalMap(input: Optional<Map<String, Any?>>) = receive("optionalMap", input)

        fun rawMap(input: LinkedHashMap<*, *>) = receive("rawMap", input)

        private fun receive(field: String, input: Any): Boolean {
            received[field] = input
            return true
        }

        private fun Part.content() = String(inputStream.readBytes())
    }

    class EventInput {
        var date: LocalDate? = null
        var opaque: Opaque? = null
        var file: Part? = null
    }

    data class MessageInput(val name: String, val date: LocalDate? = null, val file: Part? = null, val files: List<Part> = emptyList())

    data class LabelInput(val date: String? = null, val opaque: String? = null)

    class PaymentInput {
        var price: MoneyDto? = null
        var reference: OpaqueDto? = null
        var date: LocalDate? = null

        @field:JsonDeserialize(using = BoxContentDeserializer::class)
        var box: String? = null
    }

    class Opaque(text: String) {
        val value = text
    }

    data class OpaqueDto(val value: String)

    data class Money(val amount: Int, val currency: String)

    data class MoneyDto(val amount: Int, val currency: String)

    data class Box(val content: Any)

    class BoxContentDeserializer : StdDeserializer<String>(String::class.java) {
        override fun deserialize(p: JsonParser, context: DeserializationContext): String =
            p.codec.readTree<JsonNode>(p).path("content").textValue() ?: "<missing>"
    }

    class NextDayDeserializer : StdDeserializer<LocalDate>(LocalDate::class.java) {
        override fun deserialize(p: JsonParser, context: DeserializationContext): LocalDate = LocalDate.parse(p.text).plusDays(1)
    }

    class PartSerializer : StdSerializer<Part>(Part::class.java) {
        override fun serialize(value: Part, gen: JsonGenerator, provider: SerializerProvider) {
            gen.writeStartObject()
            gen.writeStringField("name", value.name)
            gen.writeBinaryField("content", value.inputStream.readBytes())
            gen.writeEndObject()
        }
    }

    class PartDeserializer : StdDeserializer<Part>(Part::class.java) {
        override fun deserialize(p: JsonParser, context: DeserializationContext): Part {
            val node = p.codec.readTree<JsonNode>(p)
            return MockPart("copy of ${node["name"].textValue()}", String(node["content"].binaryValue()))
        }
    }

    private val localDateScalar = scalar("LocalDate") { LocalDate.parse(it) }
    private val opaqueScalar = scalar("Opaque") { Opaque(it) }
    private val boxScalar = scalar("Box") { Box(if (it.startsWith("date:")) LocalDate.parse(it.removePrefix("date:")) else it) }
    private val moneyScalar = scalar("Money") { Money(it.toInt(), "CAD") }

    private fun <T : Any> scalar(name: String, parse: (String) -> T): GraphQLScalarType = GraphQLScalarType.newScalar()
        .name(name)
        .coercing(object : Coercing<T, String> {
            override fun serialize(dataFetcherResult: Any, context: GraphQLContext, locale: Locale) = dataFetcherResult.toString()
            override fun parseValue(input: Any, context: GraphQLContext, locale: Locale): T = parse(input as String)
            override fun parseLiteral(input: Value<*>, variables: CoercedVariables, context: GraphQLContext, locale: Locale): T =
                parse(checkNotNull((input as StringValue).value))
        })
        .build()
}
