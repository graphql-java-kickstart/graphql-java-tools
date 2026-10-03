package graphql.kickstart.tools

import graphql.GraphQL
import org.junit.Test

class GenericInputTypesTest {

    @Test
    fun `generic input types are parsed`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    audit(input: LanguageAudit!): String!
                    audits(input: LanguageAudits!): String!
                }

                input LanguageAudit {
                    content: LanguageInput!
                    operator: String!
                }

                input LanguageAudits {
                    content: [LanguageInput!]!
                    operator: String!
                }

                input LanguageInput {
                    id: ID!
                }
                """)
            .resolvers(QueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                audit(input: { content: { id: "1" }, operator: "op" })
                audits(input: { content: [{ id: "2" }, { id: "3" }], operator: "op" })
            }
            """
        }

        assertEquals(data["audit"], "op:1")
        assertEquals(data["audits"], "op:2,3")
    }

    @Test
    fun `generic input types inherited from a parameterized superclass are parsed`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    audit(input: LanguageAudit!): String!
                }

                input LanguageAudit {
                    content: LanguageInput!
                    operator: String!
                }

                input LanguageInput {
                    id: ID!
                }
                """)
            .resolvers(InheritedQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                audit(input: { content: { id: "1" }, operator: "op" })
            }
            """
        }

        assertEquals(data["audit"], "op:1")
    }

    @Test
    fun `one generic input class can back several input types`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    languageAudit(input: LanguageAudit!): String!
                    countryAudit(input: CountryAudit!): String!
                }

                input LanguageAudit {
                    content: LanguageInput!
                    operator: String!
                }

                input CountryAudit {
                    content: CountryInput!
                    operator: String!
                }

                input LanguageInput {
                    id: ID!
                }

                input CountryInput {
                    code: String!
                }
                """)
            .resolvers(MultipleParameterizationsQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                languageAudit(input: { content: { id: "1" }, operator: "op" })
                countryAudit(input: { content: { code: "CA" }, operator: "op" })
            }
            """
        }

        assertEquals(data["languageAudit"], "op:1")
        assertEquals(data["countryAudit"], "op:CA")
    }

    @Test
    fun `generic input types nested in a non-generic input type are parsed`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    audit(input: AuditRequest!): String!
                }

                input AuditRequest {
                    audit: LanguageAudit!
                    reason: String!
                }

                input LanguageAudit {
                    content: LanguageInput!
                    operator: String!
                }

                input LanguageInput {
                    id: ID!
                }
                """)
            .resolvers(NestedQueryResolver())
            .build()
            .makeExecutableSchema()
        val gql = GraphQL.newGraphQL(schema).build()

        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                audit(input: { audit: { content: { id: "1" }, operator: "op" }, reason: "why" })
            }
            """
        }

        assertEquals(data["audit"], "why:op:1")
    }

    class QueryResolver : GraphQLQueryResolver {
        fun audit(input: AuditWrapper<LanguageInput>): String = "${input.operator}:${input.content?.id}"
        fun audits(input: AuditWrapper<List<LanguageInput>>): String = "${input.operator}:${input.content?.joinToString(",") { it.id.orEmpty() }}"
    }

    class InheritedQueryResolver : GraphQLQueryResolver {
        fun audit(input: LanguageAuditWrapper): String = "${input.operator}:${input.content?.id}"
    }

    class MultipleParameterizationsQueryResolver : GraphQLQueryResolver {
        fun languageAudit(input: AuditWrapper<LanguageInput>): String = "${input.operator}:${input.content?.id}"
        fun countryAudit(input: AuditWrapper<CountryInput>): String = "${input.operator}:${input.content?.code}"
    }

    class NestedQueryResolver : GraphQLQueryResolver {
        fun audit(input: AuditRequest): String = "${input.reason}:${input.audit?.operator}:${input.audit?.content?.id}"
    }

    open class AuditWrapper<T> {
        var content: T? = null
        var operator: String? = null
    }

    class LanguageAuditWrapper : AuditWrapper<LanguageInput>()

    class AuditRequest {
        var audit: AuditWrapper<LanguageInput>? = null
        var reason: String? = null
    }

    class LanguageInput {
        var id: String? = null
    }

    class CountryInput {
        var code: String? = null
    }
}
