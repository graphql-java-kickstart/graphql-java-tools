package graphql.kickstart.tools

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.POJONode
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import graphql.ExceptionWhileDataFetching
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.GraphQLScalarType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import java.time.LocalDate
import java.util.*
import javax.servlet.http.Part

@OptIn(ExperimentalCoroutinesApi::class)
class CustomScalarInputTest {

    @Test
    fun `custom scalars in input objects are passed to the resolver`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                scalar LocalDate
                scalar Opaque

                type Query {
                    event(input: EventInput!): String!
                    events(inputs: [EventInput!]!): [String!]!
                    optionalEvent(input: EventInput): String!
                    eventNode(input: EventNodeInput!): String!
                }

                input EventInput {
                    date: LocalDate
                    opaque: Opaque
                }

                input EventNodeInput {
                    date: LocalDate
                }
                """)
            .scalars(localDateScalar, opaqueScalar)
            .resolvers(EventQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql, mapOf("input" to mapOf("date" to "2021-03-04", "opaque" to "variable"))) {
            """
            query(${'$'}input: EventInput!) {
                literal: event(input: { date: "2020-01-02", opaque: "literal" })
                variable: event(input: ${'$'}input)
                events(inputs: [{ date: "2020-01-02" }, { opaque: "second" }])
                optionalEvent(input: { date: "2020-01-02", opaque: "optional" })
                eventNode(input: { date: "2020-01-02" })
            }
            """
        }

        assertEquals(data, mapOf(
            "literal" to "2020-01-02 literal",
            "variable" to "2021-03-04 variable",
            "events" to listOf("2020-01-02 null", "null second"),
            "optionalEvent" to "2020-01-02 optional",
            "eventNode" to "LocalDate 2020-01-02"
        ))
    }

    @Test
    fun `uploads in input objects are passed to the resolver`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                scalar Upload

                type Query {
                    upload(input: UploadInput!): String!
                    javaUpload(input: JavaUploadInput!): String!
                }

                input UploadInput {
                    name: String!
                    file: Upload
                    files: [Upload!]
                }

                input JavaUploadInput {
                    file: Upload
                }
                """)
            .scalars(uploadScalar)
            .resolvers(UploadQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val files = mapOf("file" to MockPart("a.txt", "Hello"), "files" to listOf(MockPart("b.txt", "big"), MockPart("c.txt", "world")))
        val data = assertNoGraphQlErrors(gql, files) {
            """
            query(${'$'}file: Upload, ${'$'}files: [Upload!]) {
                upload(input: { name: "greeting", file: ${'$'}file, files: ${'$'}files })
                javaUpload(input: { file: ${'$'}file })
            }
            """
        }

        assertEquals(data, mapOf(
            "upload" to "greeting: Hello big world",
            "javaUpload" to "a.txt: Hello"
        ))
    }

    @Test
    fun `custom scalars in Map arguments are passed as coerced values`() {
        val resolver = MapQueryResolver()
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                scalar LocalDate
                scalar Money
                scalar UUID

                type Query {
                    event(input: EventInput!): String!
                    eventMap(input: EventMapInput!): Boolean!
                }

                input EventInput {
                    date: LocalDate
                    price: Money
                    id: UUID
                }

                input EventMapInput {
                    name: String
                    date: LocalDate
                    price: Money
                    id: UUID
                    next: EventMapInput
                }
                """)
            .scalars(localDateScalar, moneyScalar, uuidScalar)
            .resolvers(resolver)
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        assertNoGraphQlErrors(gql) {
            """
            query {
                eventMap(input: { price: "5" })
            }
            """
        }

        assertEquals(resolver.input, mapOf("price" to Money(5, "CAD")))

        assertNoGraphQlErrors(gql) {
            """
            query {
                eventMap(input: {
                    name: null
                    date: "2020-01-02"
                    price: "5"
                    id: "4dbd9a3d-4a26-4d10-9bb2-5b2dc4c8a0f0"
                    next: { date: "2021-03-04" }
                })
            }
            """
        }

        // the ObjectMapper has a serializer for UUID, so that still arrives in its serialized form
        assertEquals(resolver.input, mapOf(
            "name" to null,
            "date" to LocalDate.of(2020, 1, 2),
            "price" to Money(5, "CAD"),
            "id" to "4dbd9a3d-4a26-4d10-9bb2-5b2dc4c8a0f0",
            "next" to mapOf("date" to LocalDate.of(2021, 3, 4))
        ))
    }

    @Test
    fun `custom scalars the ObjectMapper can convert are still converted by it`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                scalar LocalDate
                scalar Money
                scalar Opaque
                scalar GeoPoint

                type Query {
                    payment(input: PaymentInput!): String!
                    paymentNode(input: PaymentNodeInput!): String!
                    price(price: Money!): String!
                }

                input PaymentInput {
                    price: Money
                    reference: Opaque
                    location: GeoPoint
                    date: LocalDate
                }

                input PaymentNodeInput {
                    price: Money
                }
                """)
            .scalars(localDateScalar, moneyScalar, opaqueScalar, geoPointScalar)
            .resolvers(PaymentQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                payment(input: { price: "5", reference: "ref", location: "1.5,2.5" })
                withDate: payment(input: { price: "5", reference: "ref", location: "1.5,2.5", date: "2020-01-02" })
                paymentNode(input: { price: "5" })
                price(price: "5")
            }
            """
        }

        assertEquals(data, mapOf(
            "payment" to "MoneyDto(amount=5, currency=CAD) OpaqueDto(value=ref) GeoPoint(lat=1.5, lng=2.5) null",
            "withDate" to "MoneyDto(amount=5, currency=CAD) OpaqueDto(value=ref) GeoPoint(lat=1.5, lng=2.5) 2020-01-02",
            "paymentNode" to """{"price":{"amount":5,"currency":"CAD"}}""",
            "price" to "MoneyDto(amount=5, currency=CAD)"
        ))
    }

    @Test
    fun `custom scalars in inputs are converted by a configured ObjectMapper`() {
        val resolver = MapQueryResolver()
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                scalar LocalDate
                scalar Upload

                type Query {
                    event(input: EventInput!): String!
                    eventMap(input: EventMapInput!): Boolean!
                    javaUpload(input: JavaUploadInput!): String!
                }

                input EventInput {
                    date: LocalDate
                }

                input EventMapInput {
                    name: String
                    date: LocalDate
                }

                input JavaUploadInput {
                    file: Upload
                }
                """)
            .scalars(localDateScalar, uploadScalar)
            .resolvers(resolver, UploadQueryResolver())
            .options(SchemaParserOptions.newOptions()
                .objectMapperConfigurer { mapper, _ ->
                    mapper.registerModule(SimpleModule()
                        .addSerializer(LocalDate::class.java, ToStringSerializer.instance)
                        .addDeserializer(LocalDate::class.java, NextDayDeserializer())
                        .addSerializer(Part::class.java, PartSerializer())
                        .addDeserializer(Part::class.java, PartDeserializer()))
                    mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
                }
                .build())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql, mapOf("file" to MockPart("a.txt", "Hello"))) {
            """
            query(${'$'}file: Upload) {
                event(input: { date: "2020-01-02" })
                eventMap(input: { name: null, date: "2020-01-02" })
                javaUpload(input: { file: ${'$'}file })
            }
            """
        }

        assertEquals(data, mapOf("event" to "2020-01-03 null", "eventMap" to true, "javaUpload" to "copy of a.txt: Hello"))
        assertEquals(resolver.input, mapOf("date" to "2020-01-02"))
    }

    @Test
    fun `errors converting inputs with custom scalars name the actual problem`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                scalar LocalDate

                type Query {
                    defaulted(input: DefaultedInput!): String!
                }

                input DefaultedInput {
                    name: String
                    date: LocalDate
                }
                """)
            .scalars(localDateScalar)
            .resolvers(DefaultedQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val result = gql.execute(ExecutionInput.newExecutionInput().query(
            """
            query {
                defaulted(input: { name: null, date: "2020-01-02" })
            }
            """))

        assertEquals(result.errors.size, 1)
        val exception = (result.errors[0] as ExceptionWhileDataFetching).exception
        assert(exception.message.orEmpty().contains("JSON property name due to missing")) { exception.message.orEmpty() }
        val conversionError = exception.suppressed.single()
        assert(conversionError.message.orEmpty().startsWith("Java 8 date/time type `java.time.LocalDate` not supported")) { conversionError.message.orEmpty() }
    }

    class EventQueryResolver : GraphQLQueryResolver {
        fun event(input: EventInput): String = "${input.date} ${input.opaque?.value}"
        fun events(inputs: List<EventInput>): List<String> = inputs.map { event(it) }
        fun optionalEvent(input: Optional<EventInput>): String = input.map { event(it) }.orElse("empty")
        fun eventNode(input: ObjectNode): String = (input["date"] as POJONode).pojo.let { "${it.javaClass.simpleName} $it" }
    }

    class EventInput {
        var date: LocalDate? = null
        var opaque: Opaque? = null
        var price: Money? = null
        var id: UUID? = null
    }

    class UploadQueryResolver : GraphQLQueryResolver {
        fun upload(input: UploadInput): String = "${input.name}: ${(listOfNotNull(input.file) + input.files).joinToString(" ") { it.content() }}"
        fun javaUpload(input: JavaUploadInput): String = input.file?.let { "${it.name}: ${it.content()}" }.orEmpty()

        private fun Part.content() = String(inputStream.readBytes())
    }

    data class UploadInput(val name: String, val file: Part?, val files: List<Part> = emptyList())

    class JavaUploadInput {
        var file: Part? = null
    }

    class MapQueryResolver : GraphQLQueryResolver {
        var input: Map<String, Any?>? = null

        // the scalars are only found through the fields of input classes, not of Map inputs
        fun event(input: EventInput): String = "${input.date} ${input.price}"

        fun eventMap(input: Map<String, Any?>): Boolean {
            this.input = input
            return true
        }
    }

    class PaymentQueryResolver : GraphQLQueryResolver {
        fun payment(input: PaymentInput): String = "${input.price} ${input.reference} ${input.location} ${input.date}"
        fun paymentNode(input: ObjectNode): String = input.toString()
        fun price(price: MoneyDto): String = price.toString()
    }

    class PaymentInput {
        var price: MoneyDto? = null
        var reference: OpaqueDto? = null

        @field:JsonDeserialize(using = GeoPointDeserializer::class)
        var location: GeoPoint? = null
        var date: LocalDate? = null
    }

    class DefaultedQueryResolver : GraphQLQueryResolver {
        fun defaulted(input: DefaultedInput): String = "${input.name} ${input.date}"
    }

    data class DefaultedInput(val name: String = "default", val date: LocalDate? = null)

    /**
     * Has no creator Jackson could use, like the coerced values of many custom scalars.
     */
    class Opaque private constructor(private val internalValue: String) {
        val value get() = internalValue

        companion object {
            fun of(value: String) = Opaque(value)
        }
    }

    data class OpaqueDto(val value: String)

    data class Money(val amount: Int, val currency: String)

    data class MoneyDto(val amount: Int, val currency: String)

    data class GeoPoint(val lat: Double, val lng: Double)

    // reads the serialized form, and an embedded GeoPoint wouldn't fail it
    class GeoPointDeserializer : StdDeserializer<GeoPoint>(GeoPoint::class.java) {
        override fun deserialize(p: JsonParser, context: DeserializationContext): GeoPoint {
            val node = p.codec.readTree<JsonNode>(p)
            return GeoPoint(node.path("lat").asDouble(), node.path("lng").asDouble())
        }
    }

    class NextDayDeserializer : StdDeserializer<LocalDate>(LocalDate::class.java) {
        override fun deserialize(p: JsonParser, context: DeserializationContext): LocalDate = LocalDate.parse(p.text).plusDays(1)
    }

    // the workaround from https://github.com/graphql-java-kickstart/graphql-java-tools/issues/466
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
    private val opaqueScalar = scalar("Opaque") { Opaque.of(it) }
    private val moneyScalar = scalar("Money") { Money(it.toInt(), "CAD") }
    private val uuidScalar = scalar("UUID") { UUID.fromString(it) }
    private val geoPointScalar = scalar("GeoPoint") { value -> value.split(",").map { it.toDouble() }.let { GeoPoint(it[0], it[1]) } }

    private fun <T : Any> scalar(name: String, parse: (String) -> T): GraphQLScalarType = GraphQLScalarType.newScalar()
        .name(name)
        .coercing(object : Coercing<T, String> {
            override fun serialize(dataFetcherResult: Any, context: GraphQLContext, locale: Locale) = dataFetcherResult.toString()
            override fun parseValue(input: Any, context: GraphQLContext, locale: Locale): T = parse(input as String)
            override fun parseLiteral(input: Value<*>, variables: CoercedVariables, context: GraphQLContext, locale: Locale): T = parse((input as StringValue).value!!)
        })
        .build()
}
