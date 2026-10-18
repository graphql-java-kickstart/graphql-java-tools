package graphql.kickstart.tools

import com.fasterxml.jackson.annotation.JacksonInject
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.InjectableValues
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleDeserializers
import com.fasterxml.jackson.databind.module.SimpleModule
import graphql.GraphQL
import graphql.kickstart.tools.UnmappedInputFieldsFixtures.*
import graphql.schema.DataFetchingEnvironment
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.*

class UnmappedInputFieldsTest {

    @Test
    fun `misspelled property of an input class is reported`() {
        val (schema, warnings) = withWarnings { projectParser().makeExecutableSchema() }
        // the input class misspells technologies, so createProject fails when queried
        assertEveryFieldResolves(schema, except = setOf("Mutation.createProject"))

        assertEquals(warnings, listOf(TECHNOLOGIES_MESSAGE))

        val result = GraphQL.newGraphQL(schema).build().execute(
            """
            mutation {
                createProject(input: { name: "tools", technologies: [{ name: "Kotlin" }] })
            }
            """)
        assertContains(result.errors.single().message, "Unrecognized field \"technologies\"")
    }

    @Test
    fun `misspelled property of an input class fails the build if asked to`() {
        val error = assertThrows(SchemaError::class.java) {
            projectParser(failOptions().build()).makeExecutableSchema()
        }

        assertEquals(error.message, TECHNOLOGIES_MESSAGE)
    }

    @Test
    fun `misspelled scalar property and Kotlin property named like a boolean getter are reported`() {
        val (schema, warnings) = withWarnings {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        greet(input: GreetingInput!): String
                        active(input: ActiveInput!): Boolean
                    }

                    input GreetingInput {
                        name: String!
                    }

                    input ActiveInput {
                        active: Boolean
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun greet(input: GreetingInput): String? = input.nmae
                    fun active(input: ActiveInput): Boolean = input.isActive
                })
                .build()
                .makeExecutableSchema()
        }

        // the Kotlin module keeps the "is" of a property, unlike the getter name of a Java boolean
        assertEquals(warnings, listOf(
            unmapped("GreetingInput.name", GreetingInput::class.java, "argument 'input' of field 'Query.greet'", "nmae"),
            unmapped("ActiveInput.active", ActiveInput::class.java, "argument 'input' of field 'Query.active'", "isActive")
        ))

        val result = GraphQL.newGraphQL(schema).build().execute("query { active(input: { active: true }) }")
        assertContains(result.errors.single().message, "Unrecognized field \"active\"")
    }

    @Test
    fun `misspelled properties of nested input classes are all reported through lists, arrays and Optionals`() {
        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        outer(input: OuterInput): String
                    }

                    input OuterInput {
                        middle: MiddleInput
                    }

                    input MiddleInput {
                        list: [ListItemInput]
                        array: [ArrayItemInput]
                        optional: OptionalItemInput
                    }

                    input ListItemInput {
                        name: String
                    }

                    input ArrayItemInput {
                        name: String
                    }

                    input OptionalItemInput {
                        name: String
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun outer(input: Optional<OuterInput>): String? = input.toString()
                })
                .options(failOptions().build())
                .build()
                .makeExecutableSchema()
        }

        val usage = "argument 'input' of field 'Query.outer'"
        assertEquals(error.message, listOf(
            unmapped("ListItemInput.name", ListItemInput::class.java, usage, "nmae"),
            unmapped("ArrayItemInput.name", ArrayItemInput::class.java, usage, "nmae"),
            unmapped("OptionalItemInput.name", OptionalItemInput::class.java, usage, "nmae")
        ).joinToString("\n"))
    }

    @Test
    fun `input class used by several fields is reported once, including fields from extensions`() {
        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        test: String
                    }

                    type Mutation {
                        create(input: ItemInput!): String
                        update(input: ItemInput!): String
                    }

                    input ItemInput {
                        name: String
                    }

                    extend input ItemInput {
                        label: String
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun test(): String? = null
                }, object : GraphQLMutationResolver {
                    fun create(input: ItemInput): String? = input.name
                    fun update(input: ItemInput): String? = input.name
                })
                .options(failOptions().build())
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, unmapped("ItemInput.label", ItemInput::class.java, "argument 'input' of field 'Mutation.create'", "lable, name"))
    }

    @Test
    fun `misspelled property is reported if the ObjectMapper ignores unknown properties`() {
        val (schema, warnings) = withWarnings {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        greet(input: GreetingInput!): String
                    }

                    input GreetingInput {
                        name: String!
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun greet(input: GreetingInput): String? = input.nmae
                })
                .options(SchemaParserOptions.newOptions()
                    .objectMapperConfigurer { mapper, _ -> mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) }
                    .build())
                .build()
                .makeExecutableSchema()
        }
        assertEveryFieldResolves(schema)

        assertEquals(warnings, listOf(unmapped("GreetingInput.name", GreetingInput::class.java, "argument 'input' of field 'Query.greet'", "nmae",
            "drops the field's value, since the ObjectMapper ignores unknown properties")))

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                greet(input: { name: "Ada" })
            }
            """
        }
        assertEquals(data["greet"], null)
    }

    @Test
    fun `input class is checked with the ObjectMapper of each field using it`() {
        val parser = { options: SchemaParserOptions.Builder ->
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        snake(input: NameInput!): String
                        camel(input: NameInput!): String
                    }

                    input NameInput {
                        firstName: String
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun snake(input: NameInput): String? = input.firstName
                    fun camel(input: NameInput): String? = input.firstName
                })
                .options(options
                    .objectMapperConfigurer { mapper, context ->
                        if (context.field.name == "snake") {
                            mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        }
                    }
                    .build())
                .build()
        }

        val error = assertThrows(SchemaError::class.java) {
            parser(failOptions()).makeExecutableSchema()
        }
        assertEquals(error.message, unmapped("NameInput.firstName", NameInput::class.java, "argument 'input' of field 'Query.snake'", "first_name"))

        val schema = parser(SchemaParserOptions.newOptions()).makeExecutableSchema()
        // the snake case ObjectMapper doesn't know firstName, so snake fails when queried
        assertEveryFieldResolves(schema, except = setOf("Query.snake"))
        val gql = GraphQL.newGraphQL(schema).build()
        val result = gql.execute("query { snake(input: { firstName: \"Ada\" }) camel(input: { firstName: \"Ada\" }) }")
        assertContains(result.errors.single().message, "Unrecognized field \"firstName\"")
        assertEquals(result.getData<Map<String, Any?>>()!!["camel"], "Ada")
    }

    @Test
    fun `input class is reported once for each consequence of the ObjectMappers using it`() {
        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        lenient(input: GreetingInput!): String
                        strict(input: GreetingInput!): String
                        alsoStrict(input: GreetingInput!): String
                    }

                    input GreetingInput {
                        name: String!
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun lenient(input: GreetingInput): String? = input.nmae
                    fun strict(input: GreetingInput): String? = input.nmae
                    fun alsoStrict(input: GreetingInput): String? = input.nmae
                })
                .options(failOptions()
                    .objectMapperConfigurer { mapper, context ->
                        if (context.field.name == "lenient") {
                            mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        }
                    }
                    .build())
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, listOf(
            unmapped("GreetingInput.name", GreetingInput::class.java, "argument 'input' of field 'Query.lenient'", "nmae",
                "drops the field's value, since the ObjectMapper ignores unknown properties"),
            unmapped("GreetingInput.name", GreetingInput::class.java, "argument 'input' of field 'Query.strict'", "nmae")
        ).joinToString("\n"))
    }

    @Test
    fun `misspelled property of a field with a default value is reported as failing every time`() {
        val (schema, warnings) = withWarnings {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        search(filter: SearchFilter): String
                    }

                    input SearchFilter {
                        query: String
                        limit: Int = 10
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun search(filter: SearchFilter?): String? = filter?.query
                })
                .build()
                .makeExecutableSchema()
        }

        assertEquals(warnings, listOf(unmapped("SearchFilter.limit", SearchFilter::class.java, "argument 'filter' of field 'Query.search'", "limt, query",
            "fails for every 'SearchFilter' value sent, since the field has a default value")))

        val result = GraphQL.newGraphQL(schema).build().execute("query { search(filter: { query: \"a\" }) }")
        assertContains(result.errors.single().message, "Unrecognized field \"limit\"")
    }

    @Test
    fun `arguments are matched to their parameters past sources, environments and continuations`() {
        val error = assertThrows(SchemaError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        owner: Owner
                        withEnvironment(input: EnvironmentInput!): String
                        suspending(input: SuspendInput!): String
                    }

                    type Owner {
                        child(id: ID!, input: ChildInput!): String
                    }

                    input EnvironmentInput {
                        name: String
                    }

                    input SuspendInput {
                        name: String
                    }

                    input ChildInput {
                        name: String
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun owner(): Owner = Owner()
                    fun withEnvironment(input: EnvironmentInput, environment: DataFetchingEnvironment): String? = input.nmae
                    @Suppress("RedundantSuspendModifier")
                    suspend fun suspending(input: SuspendInput): String? = input.nmae
                }, object : GraphQLResolver<Owner> {
                    fun child(owner: Owner, id: String, input: ChildInput): String? = input.nmae
                })
                .options(failOptions().build())
                .build()
                .makeExecutableSchema()
        }

        assertEquals(error.message, listOf(
            unmapped("ChildInput.name", ChildInput::class.java, "argument 'input' of field 'Owner.child'", "nmae"),
            unmapped("EnvironmentInput.name", EnvironmentInput::class.java, "argument 'input' of field 'Query.withEnvironment'", "nmae"),
            unmapped("SuspendInput.name", SuspendInput::class.java, "argument 'input' of field 'Query.suspending'", "nmae")
        ).joinToString("\n"))
    }

    @Test
    fun `input classes are not checked if asked not to`() {
        val (schema, warnings) = withWarnings {
            projectParser(SchemaParserOptions.newOptions().unmappedInputFields(UnmappedInputFields.IGNORE).build()).makeExecutableSchema()
        }
        // the input class misspells technologies, so createProject fails when queried
        assertEveryFieldResolves(schema, except = setOf("Mutation.createProject"))

        assertEquals(warnings, emptyList<String>())
    }

    @Test
    fun `Java input classes that Jackson maps are not reported`() {
        val (schema, warnings) = withWarnings {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        setterOnly(input: SetterOnlyInput!): String
                        constructorProperties(input: ConstructorPropertiesInput!): String
                        privateField(input: PrivateFieldInput!): String
                        alias(input: AliasInput!): String
                        anySetter(input: AnySetterInput!): String
                        ignoreUnknown(input: IgnoreUnknownInput!): String
                        ignoredName(input: IgnoredNameInput!): String
                        jsonIgnore(input: JsonIgnoreInput!): String
                        unwrapped(input: UnwrappedInput!): String
                    }

                    input SetterOnlyInput {
                        name: String
                        nested: NestedInput
                    }

                    input NestedInput {
                        value: String
                    }

                    input ConstructorPropertiesInput {
                        name: String
                    }

                    input PrivateFieldInput {
                        name: String
                    }

                    input AliasInput {
                        name: String
                    }

                    input AnySetterInput {
                        a: Int
                        b: String
                    }

                    input IgnoreUnknownInput {
                        name: String
                        other: String
                    }

                    input IgnoredNameInput {
                        name: String
                        legacy: String
                    }

                    input JsonIgnoreInput {
                        name: String
                        secret: String
                    }

                    input UnwrappedInput {
                        name: String
                        street: String
                    }
                    """)
                .resolvers(object : GraphQLQueryResolver {
                    fun setterOnly(input: SetterOnlyInput): String = input.describe()
                    fun constructorProperties(input: ConstructorPropertiesInput): String = input.describe()
                    fun privateField(input: PrivateFieldInput): String = input.describe()
                    fun alias(input: AliasInput): String = input.describe()
                    fun anySetter(input: AnySetterInput): String = input.describe()
                    fun ignoreUnknown(input: IgnoreUnknownInput): String = input.name
                    fun ignoredName(input: IgnoredNameInput): String = input.name
                    fun jsonIgnore(input: JsonIgnoreInput): String = "${input.name} ${input.secret}"
                    fun unwrapped(input: UnwrappedInput): String = "${input.name} ${input.address.street}"
                })
                .options(failOptions().build())
                .build()
                .makeExecutableSchema()
        }
        assertEveryFieldResolves(schema)

        assertEquals(warnings, emptyList<String>())

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                setterOnly(input: { name: "a", nested: { value: "b" } })
                constructorProperties(input: { name: "d" })
                privateField(input: { name: "e" })
                alias(input: { name: "f" })
                anySetter(input: { a: 1, b: "g" })
                ignoreUnknown(input: { name: "h", other: "x" })
                ignoredName(input: { name: "i", legacy: "x" })
                jsonIgnore(input: { name: "j", secret: "x" })
                unwrapped(input: { name: "k", street: "l" })
            }
            """
        }

        assertEquals(data, mapOf(
            "setterOnly" to "a b",
            "constructorProperties" to "d",
            "privateField" to "e",
            "alias" to "f",
            "anySetter" to "{a=1, b=g}",
            "ignoreUnknown" to "h",
            "ignoredName" to "i",
            "jsonIgnore" to "j null",
            "unwrapped" to "k l"
        ))
    }

    @Test
    fun `Kotlin input classes and other arguments that Jackson maps are not reported`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    dataClass(input: DataClassInput!): String
                    snakeCase(input: SnakeCaseInput!): String
                    caseInsensitive(input: CaseInsensitiveInput!): String
                    propertyIgnorals(input: PropertyIgnoralsInput!): String
                    filter(input: RequestFilter!): String
                    beanMap(input: BeanMapInput!): String
                }

                input DataClassInput {
                    name: String!
                    count: Int
                }

                input SnakeCaseInput {
                    first_name: String
                }

                input CaseInsensitiveInput {
                    NAME: String
                }

                input PropertyIgnoralsInput {
                    inner: InnerInput
                    optionalInner: InnerInput
                }

                input InnerInput {
                    name: String
                    legacy: String
                }

                input RequestFilter {
                    and: [RequestFilter!]
                    or: [RequestFilter!]
                    name: String
                }

                input BeanMapInput {
                    first: RequestFilter
                    second: RequestFilter
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun dataClass(input: DataClassInput): String = input.toString()
                fun snakeCase(input: SnakeCaseInput): String? = input.firstName
                fun caseInsensitive(input: CaseInsensitiveInput): String? = input.name
                fun propertyIgnorals(input: PropertyIgnoralsInput): String = "${input.inner?.name}, ${input.optionalInner?.get()?.name}"
                fun filter(input: RequestFilter): String? = input.and?.single()?.or?.single()?.name
                fun beanMap(input: Map<String, RequestFilter>): String = input.mapValues { it.value.name }.toString()
            })
            .options(failOptions().build())
            .build()
            .makeExecutableSchema()
        assertEveryFieldResolves(schema)

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                dataClass(input: { name: "a" })
                snakeCase(input: { first_name: "d" })
                caseInsensitive(input: { NAME: "e" })
                propertyIgnorals(input: { inner: { name: "f", legacy: "x" }, optionalInner: { name: "m", legacy: "y" } })
                filter(input: { and: [{ or: [{ name: "g" }] }] })
                beanMap(input: { first: { name: "k" }, second: { name: "l" } })
            }
            """
        }

        assertEquals(data, mapOf(
            "dataClass" to "DataClassInput(name=a, count=1)",
            "snakeCase" to "d",
            "caseInsensitive" to "e",
            "propertyIgnorals" to "f, m",
            "filter" to "g",
            "beanMap" to "{first=k, second=l}"
        ))
    }

    @Test
    fun `input classes that Jackson doesn't deserialize property by property are not reported`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    delegating(input: DelegatingInput!): String
                    builder(input: BuilderInput!): String
                    shape(input: ShapeInput!): String
                    animals(input: [AnimalInput!]!): String
                    animal(input: AnimalInput): String
                    zoo(input: ZooInput!): String
                    money(input: MoneyInput!): String
                }

                input DelegatingInput {
                    name: String
                }

                input BuilderInput {
                    name: String
                }

                input ShapeInput {
                    kind: String!
                    radius: Float
                    side: Float
                }

                input AnimalInput {
                    kind: String!
                    name: String
                    barks: Boolean
                }

                input PetInput {
                    kind: String!
                    name: String
                    meows: Boolean
                }

                input ZooInput {
                    animal: AnimalInput
                    animals: [AnimalInput!]
                    pets: [PetInput!]
                }

                input MoneyInput {
                    cents: Int
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun delegating(input: DelegatingInput): String = input.values.toString()
                fun builder(input: BuilderInput): String? = input.name
                fun shape(input: ShapeInput): String = when (input) {
                    is Circle -> "circle ${input.radius}"
                    is Square -> "square ${input.side}"
                }
                fun animals(input: List<Animal>): String = input.joinToString { it.describe() }
                fun animal(input: Optional<Animal>): String = input.map { it.describe() }.orElse("none")
                fun zoo(input: Zoo): String =
                    listOfNotNull(input.animal?.describe(), input.animals?.single()?.describe(), input.pets?.single()?.describe()).joinToString()
                fun money(input: Money): String = input.amount.toString()
            })
            .options(failOptions()
                .objectMapperConfigurer { mapper, _ -> mapper.registerModule(SimpleModule().addDeserializer(Money::class.java, MoneyDeserializer())) }
                .build())
            .build()
            .makeExecutableSchema()
        // the made-up kind isn't one of the type ids Jackson knows
        assertEveryFieldResolves(schema, except = setOf("Query.shape(input:)", "Query.animals(input:)", "Query.animal(input:)", "Query.zoo(input:)"))

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build()) {
            """
            query {
                delegating(input: { name: "a" })
                builder(input: { name: "b" })
                shape(input: { kind: "circle", radius: 1.5 })
                animals(input: [{ kind: "dog", name: "c", barks: true }, { kind: "animal", name: "d" }])
                animal(input: { kind: "dog", name: "e", barks: false })
                zoo(input: {
                    animal: { kind: "dog", name: "f", barks: true }
                    animals: [{ kind: "dog", name: "g", barks: true }]
                    pets: [{ kind: "cat", name: "h", meows: true }]
                })
                money(input: { cents: 250 })
            }
            """
        }

        assertEquals(data, mapOf(
            "delegating" to "{name=a}",
            "builder" to "b",
            "shape" to "circle 1.5",
            "animals" to "dog c barks=true, animal d",
            "animal" to "dog e barks=false",
            "zoo" to "dog f barks=true, dog g barks=true, cat h meows=true",
            "money" to "250"
        ))
    }

    @Test
    fun `unmapped fields that can't be checked are not reported`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    injected(input: InjectedInput!): String
                    builder(input: BuilderTypoInput!): String
                    unwrapped(input: KotlinUnwrappedInput!): String
                    generic(input: GenericInput!): String
                }

                input InjectedInput {
                    name: String
                }

                input BuilderTypoInput {
                    name: String
                }

                input KotlinUnwrappedInput {
                    name: String
                    street: String
                }

                input GenericInput {
                    name: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun injected(input: InjectedInput): String? = input.nmae
                fun builder(input: BuilderTypoInput): String? = input.name
                fun unwrapped(input: KotlinUnwrappedInput): String = "${input.name} ${input.address?.stret}"
            }, GenericInputResolver())
            .options(failOptions()
                .objectMapperConfigurer { mapper, _ -> mapper.setInjectableValues(InjectableValues.Std().addValue("tenant", "acme")) }
                .build())
            .build()
            .makeExecutableSchema()
        assertEveryFieldResolves(schema, except = setOf(
            // the input classes misspell name on purpose
            "Query.injected", "Query.builder",
            // MethodFieldResolver converts the argument with the unresolved type variable of the inherited parameter
            "Query.generic"
        ))

        val result = GraphQL.newGraphQL(schema).build().execute(
            """
            query {
                injected(input: { name: "a" })
                builder(input: { name: "b" })
                unwrapped(input: { name: "c", street: "d" })
            }
            """)

        assertEquals(result.errors.map { it.path }, listOf(listOf("injected"), listOf("builder")))
        result.errors.forEach { assertContains(it.message, "Unrecognized field \"name\"") }
        // Jackson drops what none of the unwrapped properties know
        assertEquals(result.getData<Map<String, Any?>>()!!["unwrapped"], "c null")
    }

    @Test
    fun `input classes whose deserializer Jackson can't look up don't fail the build`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    broken(input: BrokenInput!): String
                    missingClass(input: GreetingInput!): String
                }

                input BrokenInput {
                    name: String
                }

                input GreetingInput {
                    name: String!
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun broken(input: ConflictingCreatorsInput): String = input.toString()
                fun missingClass(input: GreetingInput): String? = input.nmae
            })
            .options(failOptions()
                .objectMapperConfigurer { mapper, context ->
                    if (context.field.name == "missingClass") {
                        mapper.registerModule(SimpleModule().apply { setDeserializers(MissingClassDeserializers()) })
                    }
                }
                .build())
            .build()
            .makeExecutableSchema()

        val result = GraphQL.newGraphQL(schema).build().execute("query { broken(input: { name: \"a\" }) }")
        assertContains(result.errors.single().message, "Conflicting property-based creators")
    }

    private fun failOptions() = SchemaParserOptions.newOptions().unmappedInputFields(UnmappedInputFields.FAIL)

    private fun assertContains(actual: String, expected: String) = assert(actual.contains(expected)) { "expected:<$actual> to contain:<$expected>" }

    private fun projectParser(options: SchemaParserOptions = SchemaParserOptions.newOptions().build()) = SchemaParser.newParser()
        .schemaString(
            """
            type Query {
                test: String
            }

            type Mutation {
                createProject(input: ProjectInput!): String
            }

            input ProjectInput {
                id: Int
                name: String
                description: String
                technologies: [TechnologyInput!]
                profiles: [ID!]
            }

            input TechnologyInput {
                id: Int
                name: String!
            }
            """)
        .resolvers(object : GraphQLQueryResolver {
            fun test(): String? = null
        }, object : GraphQLMutationResolver {
            fun createProject(input: ProjectInput): String? = input.name
        })
        .options(options)
        .build()

    private fun unmapped(field: String, clazz: Class<*>, usage: String, knownProperties: String, consequence: String = "fails whenever the field is sent") =
        "Input field '$field' has no matching property in ${clazz.name}. Converting $usage $consequence. Known properties: $knownProperties. " +
            "Rename the property or map it with @JsonProperty(\"${field.substringAfter('.')}\"). If it's left unmapped on purpose, list it in @JsonIgnoreProperties on the class."

    class GreetingInput {
        var nmae: String? = null
    }

    data class ActiveInput(val isActive: Boolean = false)

    class OuterInput {
        var middle: MiddleInput? = null
    }

    class MiddleInput {
        var list: List<ListItemInput>? = null
        var array: Array<ArrayItemInput>? = null
        var optional: Optional<OptionalItemInput>? = null
    }

    class ListItemInput {
        var nmae: String? = null
    }

    class ArrayItemInput {
        var nmae: String? = null
    }

    class OptionalItemInput {
        var nmae: String? = null
    }

    class ItemInput {
        var name: String? = null
        var lable: String? = null
    }

    class NameInput {
        var firstName: String? = null
    }

    class SearchFilter {
        var query: String? = null
        var limt: Int? = null
    }

    class Owner

    class EnvironmentInput {
        var nmae: String? = null
    }

    class SuspendInput {
        var nmae: String? = null
    }

    class ChildInput {
        var nmae: String? = null
    }

    data class DataClassInput(val name: String, val count: Int = 1)

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
    class SnakeCaseInput {
        var firstName: String? = null
    }

    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
    class CaseInsensitiveInput {
        var name: String? = null
    }

    class PropertyIgnoralsInput {
        @field:JsonIgnoreProperties("legacy")
        var inner: InnerInput? = null

        @field:JsonIgnoreProperties("legacy")
        var optionalInner: Optional<InnerInput>? = null
    }

    class InnerInput {
        var name: String? = null
    }

    class RequestFilter {
        var and: List<RequestFilter>? = null
        var or: List<RequestFilter>? = null
        var name: String? = null
    }

    class DelegatingInput @JsonCreator(mode = JsonCreator.Mode.DELEGATING) constructor(val values: Map<String, Any?>)

    @JsonDeserialize(builder = BuilderInput.Builder::class)
    class BuilderInput private constructor(val name: String?) {
        @JsonPOJOBuilder(withPrefix = "")
        class Builder {
            private var name: String? = null

            fun name(name: String?) = apply { this.name = name }

            fun build() = BuilderInput(name)
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(Circle::class, name = "circle"), JsonSubTypes.Type(Square::class, name = "square"))
    sealed class ShapeInput

    class Circle : ShapeInput() {
        var radius: Double? = null
    }

    class Square : ShapeInput() {
        var side: Double? = null
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(Animal::class, name = "animal"), JsonSubTypes.Type(Dog::class, name = "dog"))
    open class Animal {
        var name: String? = null

        open fun describe() = "animal $name"
    }

    class Dog : Animal() {
        var barks: Boolean? = null

        override fun describe() = "dog $name barks=$barks"
    }

    open class Pet {
        var name: String? = null

        open fun describe() = "pet $name"
    }

    class Cat : Pet() {
        var meows: Boolean? = null

        override fun describe() = "cat $name meows=$meows"
    }

    class Zoo {
        var animal: Animal? = null
        var animals: List<Animal>? = null

        @field:JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @field:JsonSubTypes(JsonSubTypes.Type(Cat::class, name = "cat"))
        var pets: List<Pet>? = null
    }

    class Money(val amount: Int)

    class MoneyDeserializer : StdDeserializer<Money>(Money::class.java) {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Money = Money(p.readValueAsTree<JsonNode>()["cents"].asInt())
    }

    class InjectedInput {
        @field:JacksonInject("tenant")
        var tenant: String? = null
        var nmae: String? = null
    }

    @JsonDeserialize(builder = BuilderTypoInput.Builder::class)
    class BuilderTypoInput private constructor(val name: String?) {
        @JsonPOJOBuilder(withPrefix = "")
        class Builder {
            private var nmae: String? = null

            fun nmae(nmae: String?) = apply { this.nmae = nmae }

            fun build() = BuilderTypoInput(nmae)
        }
    }

    class KotlinUnwrappedInput {
        var name: String? = null

        @field:JsonUnwrapped
        var address: KotlinAddress? = null
    }

    class KotlinAddress {
        var stret: String? = null
    }

    class MissingClassDeserializers : SimpleDeserializers() {
        override fun findBeanDeserializer(type: JavaType, config: DeserializationConfig, beanDesc: BeanDescription): JsonDeserializer<*> =
            throw NoClassDefFoundError("com/example/Missing")
    }

    abstract class InputResolver<I> : GraphQLQueryResolver {
        fun generic(input: I): String = input.toString()
    }

    class GenericInputResolver : InputResolver<GenericInput>()

    class GenericInput {
        var nmae: String? = null
    }

    companion object {
        private val TECHNOLOGIES_MESSAGE = "Input field 'ProjectInput.technologies' has no matching property in ${ProjectInput::class.java.name}. " +
            "Converting argument 'input' of field 'Mutation.createProject' fails whenever the field is sent. " +
            "Known properties: description, id, name, profiles, technolgies. " +
            "Rename the property or map it with @JsonProperty(\"technologies\"). If it's left unmapped on purpose, list it in @JsonIgnoreProperties on the class."
    }
}
