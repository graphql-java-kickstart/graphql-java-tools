package graphql.kickstart.tools

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.node.ObjectNode
import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import org.junit.Test

class NestedInputTypesTest {

    @Test
    fun `nested input types are parsed`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    materials(filter: MaterialFilter): [Material!]!
                }
                
                input MaterialFilter {
                    title: String
                    requestFilter: RequestFilter
                }
                
                input RequestFilter {
                    and: [RequestFilter!]
                    or: [RequestFilter!]
                    discountTypeFilter: DiscountTypeFilter
                }
                
                input DiscountTypeFilter {
                    name: String
                }
                
                type Material {
                    id: ID!
                }
                """)
            .resolvers(QueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()
        val data = assertNoGraphQlErrors(gql, mapOf("filter" to mapOf("title" to "title", "requestFilter" to mapOf("discountTypeFilter" to mapOf("name" to "discount"))))) {
            """
            query materials(${'$'}filter: MaterialFilter!) {
                materials(filter: ${'$'}filter) {
                    id
                }
            }
            """
        }

        assertEquals((data["materials"]), emptyList<Any>())
    }

    @Test
    fun `nested input in extensions are parsed`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    materials(filter: MaterialFilter): [Material!]!
                }
                
                input MaterialFilter {
                    title: String
                }
                
                extend input MaterialFilter {
                    requestFilter: RequestFilter
                }
                
                input RequestFilter {
                    and: [RequestFilter!]
                    or: [RequestFilter!]
                    discountTypeFilter: DiscountTypeFilter
                }
                
                input DiscountTypeFilter {
                    name: String
                }
                
                type Material {
                    id: ID!
                }
                """)
            .resolvers(QueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema)
            .queryExecutionStrategy(AsyncExecutionStrategy())
            .build()
        val data = assertNoGraphQlErrors(gql, mapOf("filter" to mapOf("title" to "title", "requestFilter" to mapOf("discountTypeFilter" to mapOf("name" to "discount"))))) {
            """
            query materials(${'$'}filter: MaterialFilter!) {
                materials(filter: ${'$'}filter) {
                   id
                }
            }
            """
        }

        assertEquals((data["materials"]), emptyList<Any>())
    }

    @Test
    fun `nested input types are found through a Map parameter`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    discountName(filter: MaterialFilter!): String
                }

                input MaterialFilter {
                    title: String
                    requestFilter: RequestFilter
                }

                input RequestFilter {
                    and: [RequestFilter!]
                    or: [RequestFilter!]
                    discountTypeFilter: DiscountTypeFilter
                }

                input DiscountTypeFilter {
                    name: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun discountName(filter: Map<String, Any?>): String? {
                    val requestFilter = filter["requestFilter"] as Map<*, *>
                    val discountTypeFilter = (requestFilter["and"] as List<*>).single() as Map<*, *>
                    return (discountTypeFilter["discountTypeFilter"] as Map<*, *>)["name"] as String?
                }
            })
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                discountName(filter: { requestFilter: { and: [{ discountTypeFilter: { name: "discount" } }] } })
            }
            """
        }

        assertEquals(data["discountName"], "discount")
    }

    @Test
    fun `nested input types are found through ObjectNode parameters`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    test: String
                }

                type Mutation {
                    create(input: EntityCreateInput!): String
                    update(input: EntityUpdateInput!): String
                }

                input EntityCreateInput {
                    name: String
                    nested: Nested
                }

                input EntityUpdateInput {
                    id: ID!
                    nested: Nested
                }

                input Nested {
                    x: Int
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun test(): String? = null
            }, object : GraphQLMutationResolver {
                fun create(input: ObjectNode): String = input.toString()
                fun update(input: ObjectNode): String = input.toString()
            })
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            mutation {
                create(input: { name: "n", nested: { x: 1 } })
                update(input: { id: "2", nested: { x: 3 } })
            }
            """
        }

        assertEquals(data["create"], """{"name":"n","nested":{"x":1}}""")
        assertEquals(data["update"], """{"id":"2","nested":{"x":3}}""")
    }

    @Test
    fun `nested input types are found when the input class has no matching property`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    test: String
                }

                type Mutation {
                    createAgreement(data: AgreementCreateInput!): String
                }

                input AgreementCreateInput {
                    name: String
                    IncludedAgreement_back: IncludedAgreementCreateManyInput
                }

                input IncludedAgreementCreateManyInput {
                    create: [IncludedAgreementCreateInput!]
                }

                input IncludedAgreementCreateInput {
                    name: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun test(): String? = null
            }, object : GraphQLMutationResolver {
                fun createAgreement(data: AgreementCreateInput): String? =
                    data.getIncludedAgreement_Back()?.create?.joinToString { it.name.orEmpty() }
            })
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            mutation {
                createAgreement(data: { name: "a", IncludedAgreement_back: { create: [{ name: "b" }, { name: "c" }] } })
            }
            """
        }

        assertEquals(data["createAgreement"], "b, c")
    }

    @Test
    fun `nested input type reached without a class is scanned again once its class is found`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    byMap(filter: ColorFilter): String
                    byClass(color: ColorInput): String
                }

                input ColorFilter {
                    color: ColorInput
                }

                input ColorInput {
                    value: Color
                }

                enum Color {
                    RED
                    GREEN
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun byMap(filter: Map<String, Any?>): String = (filter["color"] as Map<*, *>)["value"].toString()
                fun byClass(color: ColorInput): String = color.value.toString()
            })
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                byMap(filter: { color: { value: RED } })
                byClass(color: { value: GREEN })
            }
            """
        }

        assertEquals(data["byMap"], "RED")
        assertEquals(data["byClass"], "GREEN")
    }

    @Test
    fun `list field of a nested input type reached without a class uses the dictionary class of its element`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    byMap(filter: ShipmentFilter): String
                    byClass(shipment: ShipmentInput): String
                }

                input ShipmentFilter {
                    shipment: ShipmentInput
                }

                input ShipmentInput {
                    lines: [ShipmentLineInput!]
                }

                input ShipmentLineInput {
                    sku: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun byMap(filter: Map<String, Any?>): String = filter["shipment"].toString()
                fun byClass(shipment: ShipmentInput): String = shipment.lines.orEmpty().joinToString { it.sku.orEmpty() }
            })
            .dictionary("ShipmentLineInput", ShipmentLineInput::class.java)
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                byMap(filter: { shipment: { lines: [{ sku: "a" }] } })
                byClass(shipment: { lines: [{ sku: "b" }, { sku: "c" }] })
            }
            """
        }

        assertEquals(data["byMap"], "{lines=[{sku=a}]}")
        assertEquals(data["byClass"], "b, c")
    }

    @Test
    fun `input fields bound to ObjectNode are not matched against its own methods`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    create(input: EntityInput!): String
                }

                input EntityInput {
                    values: [Nested]
                    elements: [Nested]
                    fields: [Nested]
                }

                input Nested {
                    x: Int
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun create(input: ObjectNode): String = input.toString()
            })
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                create(input: { values: [{ x: 1 }], elements: [{ x: 2 }], fields: [{ x: 3 }] })
            }
            """
        }

        assertEquals(data["create"], """{"values":[{"x":1}],"elements":[{"x":2}],"fields":[{"x":3}]}""")
    }

    @Test
    fun `input field types are found through the getters of a Map subclass`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    kind(filter: KindFilter!): String
                    nested(filter: NestedKindFilter!): String
                }

                input KindFilter {
                    kind: Kind
                }

                input NestedKindFilter {
                    inner: InnerKindFilter
                }

                input InnerKindFilter {
                    kind: Kind
                }

                enum Kind {
                    A
                    B
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun kind(filter: KindFilterMap): String = filter.kind.toString()
                fun nested(filter: NestedKindFilterMap): String = filter["inner"].toString()
            })
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                kind(filter: { kind: B })
                nested(filter: { inner: { kind: A } })
            }
            """
        }

        assertEquals(data["kind"], "B")
        assertEquals(data["nested"], "{kind=A}")
    }

    class KindFilterMap : HashMap<String, Any?>() {
        val kind: Kind? get() = this["kind"]?.let { Kind.valueOf(it.toString()) }
    }

    class NestedKindFilterMap : HashMap<String, Any?>() {
        val inner: InnerKindFilter? get() = null
    }

    class InnerKindFilter {
        var kind: Kind? = null
    }

    enum class Kind { A, B }

    class AgreementCreateInput {
        var name: String? = null

        // the getter doesn't follow the bean naming of the schema field, so only Jackson knows about the property
        @field:JsonProperty("IncludedAgreement_back")
        private var includedAgreementBack: IncludedAgreementCreateManyInput? = null

        fun getIncludedAgreement_Back(): IncludedAgreementCreateManyInput? = includedAgreementBack
    }

    class IncludedAgreementCreateManyInput {
        var create: List<IncludedAgreementCreateInput>? = null
    }

    class IncludedAgreementCreateInput {
        var name: String? = null
    }

    class ColorInput {
        var value: Color? = null
    }

    enum class Color { RED, GREEN }

    class ShipmentInput {
        var lines: List<ShipmentLineInput>? = null
    }

    class ShipmentLineInput {
        var sku: String? = null
    }

    class QueryResolver : GraphQLQueryResolver {
        fun materials(filter: MaterialFilter): List<Material> = listOf()
    }

    class Material {
        var id: Long? = null
    }

    class MaterialFilter {
        var title: String? = null
        var requestFilter: RequestFilter? = null
    }

    class RequestFilter {
        var and: List<RequestFilter>? = null
        var or: List<RequestFilter>? = null
        var discountTypeFilter: DiscountTypeFilter? = null
    }

    class DiscountTypeFilter {
        var name: String? = null
    }
}
