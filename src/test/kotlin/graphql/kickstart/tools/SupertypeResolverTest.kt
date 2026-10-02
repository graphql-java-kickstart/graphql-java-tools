package graphql.kickstart.tools

import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import graphql.schema.GraphQLSchema
import org.junit.Test

class SupertypeResolverTest {

    private val schema: GraphQLSchema = SchemaParser.newParser()
        .schemaString(
            """
            type Query {
                thing: Thing!
                otherThing: OtherThing!
            }

            type Thing {
                name: String!
                active: Boolean!
                label: String!
                description: String!
            }

            type OtherThing {
                name: String!
                active: Boolean!
                label: String!
                description: String!
            }
            """)
        .resolvers(QueryResolver(), BaseResolver(), NamedResolver(), OtherThingResolver())
        .build()
        .makeExecutableSchema()
    private val gql: GraphQL = GraphQL.newGraphQL(schema)
        .queryExecutionStrategy(AsyncExecutionStrategy())
        .build()

    @Test
    fun `resolvers for a superclass or interface should resolve fields of subclasses`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                thing {
                    name
                    active
                    label
                    description
                }
            }
            """
        }

        assertEquals(data["thing"], mapOf(
            "name" to "thing",
            "active" to true,
            "label" to "base label",
            "description" to "named description"
        ))
    }

    @Test
    fun `more specific resolvers should take precedence over supertype resolvers`() {
        val data = assertNoGraphQlErrors(gql) {
            """
            query {
                otherThing {
                    name
                    active
                    label
                    description
                }
            }
            """
        }

        assertEquals(data["otherThing"], mapOf(
            "name" to "other thing",
            "active" to false,
            "label" to "other label",
            "description" to "named description"
        ))
    }

    class QueryResolver : GraphQLQueryResolver {
        fun thing(): Thing = Thing()
        fun otherThing(): OtherThing = OtherThing()
    }

    interface Named {
        val name: String
    }

    abstract class Base : Named

    class Thing : Base() {
        override val name = "thing"
    }

    class OtherThing : Base() {
        override val name = "other thing"
    }

    class BaseResolver : GraphQLResolver<Base> {
        fun isActive(base: Base): Boolean = true
        fun label(base: Base): String = "base label"
    }

    class NamedResolver : GraphQLResolver<Named> {
        fun label(named: Named): String = "named label"
        fun description(named: Named): String = "named description"
    }

    class OtherThingResolver : GraphQLResolver<OtherThing> {
        fun isActive(otherThing: OtherThing): Boolean = false
        fun label(otherThing: OtherThing): String = "other label"
    }
}
