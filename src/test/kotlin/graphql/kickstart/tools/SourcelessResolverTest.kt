package graphql.kickstart.tools

import graphql.GraphQL
import graphql.GraphQLContext
import graphql.execution.DataFetcherResult
import graphql.kickstart.tools.resolver.FieldResolverError
import graphql.kickstart.tools.resolver.MissingResolverDataFetcherProvider
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.junit.Assert.assertThrows
import org.junit.Test

class SourcelessResolverTest {

    private val folderSchema = """
        type Query {
            folder: Folder!
        }

        type Folder {
            images: [Image!]!
        }
        """

    @Test
    fun `resolver methods without the source take the environment, GraphQLContext or the context class`() {
        val schema = build(
            """
            type Image {
                name: String!
                imageBlob: String!
                thumbnail(size: Int!): String!
                owner: String
                locale: String!
                signature: String!
                caption: String!
            }
            """,
            SourcelessImageResolver(),
            options = SchemaParserOptions.newOptions().contextClass(AppContext::class).build()
        )
        val context = mapOf<Any, Any>(AppContext::class.java to AppContext("ada", "en-CA"), "owner" to "grace")
        schema.assertEveryFieldResolves { graphQLContext(context) }

        val data = assertNoGraphQlErrors(GraphQL.newGraphQL(schema).build(), context = context) {
            """
            query {
                folder {
                    images {
                        name
                        imageBlob
                        thumbnail(size: 2)
                        owner
                        locale
                        signature
                        caption
                    }
                }
            }
            """
        }

        assertEquals(data, mapOf("folder" to mapOf("images" to listOf(mapOf(
            "name" to "cat",
            "imageBlob" to "blob for [png, jpg]",
            "thumbnail" to "png@2",
            "owner" to "grace",
            "locale" to "en-CA",
            "signature" to "signature by ada",
            "caption" to "caption of cat"
        )))))
    }

    @Test
    fun `methods with the source win over methods without it`() {
        val data = query(
            """
            type Image {
                name: String!
                title: String!
                subtitle: String!
                label: String!
            }
            """,
            ShadowedImageResolver()
        ) {
            """
            query {
                folder {
                    images {
                        name
                        title
                        subtitle
                        label
                    }
                }
            }
            """
        }

        assertEquals(data, mapOf("folder" to mapOf("images" to listOf(mapOf(
            "name" to "cat",
            "title" to "with source cat",
            "subtitle" to "with source cat",
            "label" to "getLabel cat"
        )))))
    }

    @Test
    fun `a field of the resolver wins over a method without the source`() {
        val data = query(
            """
            type Image {
                title: String!
            }
            """,
            FieldImageResolver()
        ) { imagesQuery("title") }

        assertEquals(data, mapOf("folder" to mapOf("images" to listOf(mapOf("title" to "resolver field")))))
    }

    @Test
    fun `a data class that is a Map wins over a method without the source`() {
        val data = query(
            """
            type Image {
                name: String!
            }

            extend type Query {
                tags: Tags!
            }

            type Tags {
                title: String!
            }
            """,
            TagsQuery(), TagsResolver()
        ) {
            """
            query {
                tags {
                    title
                }
            }
            """
        }

        assertEquals(data, mapOf("tags" to mapOf("title" to "map value")))
    }

    @Test
    fun `root resolvers that take the environment are still ambiguous when more than one matches`() {
        val error = assertThrows(FieldResolverError::class.java) {
            SchemaParser.newParser()
                .schemaString(
                    """
                    type Query {
                        field: Int!
                    }
                    """)
                .resolvers(FirstRootResolver(), SecondRootResolver())
                .build()
                .makeExecutableSchema()
        }

        assert("Found more than one matching resolver for field" in error.message!!) { error.message!! }
    }

    @Test
    fun `methods without the source need an environment or context parameter`() {
        val error = assertThrows(FieldResolverError::class.java) {
            build(
                """
                type Image {
                    preview: String
                    reviews: String
                }
                """,
                NoTrailingImageResolver()
            )
        }

        assert(error.message!!.contains("${NoTrailingImageResolver::class.java.name}.preview(<environment or context>)")) { error.message!! }
    }

    @Test
    fun `extension functions on the environment are not methods without the source`() {
        assertThrows(FieldResolverError::class.java) {
            build(
                """
                type Image {
                    blob: String
                }
                """,
                ExtensionImageResolver()
            )
        }
    }

    @Test
    fun `an argument may not land on a context parameter of a method without the source`() {
        val error = assertThrows(FieldResolverError::class.java) {
            build(
                """
                type Image {
                    thumbnail(size: Int): String
                }
                """,
                MisboundImageResolver()
            )
        }

        assert(error.message!!.contains("${MisboundImageResolver::class.java.name}.thumbnail(~size, <environment or context>)")) { error.message!! }
    }

    @Test
    fun `an argument may not land on a first parameter that could take the source`() {
        listOf(MistypedSourceResolver(), AnySourceResolver()).forEach { resolver ->
            assertThrows(FieldResolverError::class.java) {
                build(
                    """
                    type Image {
                        title(lang: String): String
                    }
                    """,
                    resolver
                )
            }
        }
    }

    @Test
    fun `unimplemented resolvers leave a method without the source unused`() {
        val (schema, warnings) = withWarnings {
            build(
                """
                type Image {
                    name: String!
                    preview: String
                }
                """,
                HelperImageResolver(),
                options = SchemaParserOptions.newOptions().allowUnimplementedResolvers(true).build()
            )
        }
        schema.assertEveryFieldResolves(except = setOf("Image.preview"))

        val error = assertThrows(Exception::class.java) {
            GraphQL.newGraphQL(schema).build().execute(imagesQuery("preview"))
        }
        assert("Schema resolver not implemented" in error.toString()) { error.toString() }
        assertEquals(
            warnings.filterNot { it.startsWith("Option 'allowUnimplementedResolvers'") },
            listOf("Missing resolver for field: ${checkNotNull(schema.getObjectType("Image")).getFieldDefinition("preview").definition}")
        )
    }

    @Test
    fun `the missing resolver data fetcher provider keeps fields that only have a method without the source`() {
        val provider = MissingResolverDataFetcherProvider { _, _ -> DataFetcher { "provided" } }
        val data = query(
            """
            type Image {
                preview: String
            }
            """,
            HelperImageResolver(),
            options = SchemaParserOptions.newOptions().missingResolverDataFetcherProvider(provider).build()
        ) { imagesQuery("preview") }

        assertEquals(data, mapOf("folder" to mapOf("images" to listOf(mapOf("preview" to "provided")))))
    }

    @Test
    fun `supertype resolvers' methods without the source resolve fields of subclasses, after the specific resolver's`() {
        val data = query(
            """
            type Image {
                title: String!
                label: String!
                kind: String!
            }
            """,
            SpecificImageResolver(), NamedResolver()
        ) {
            """
            query {
                folder {
                    images {
                        title
                        label
                        kind
                    }
                }
            }
            """
        }

        assertEquals(data, mapOf("folder" to mapOf("images" to listOf(mapOf(
            "title" to "named title cat",
            "label" to "specific label",
            "kind" to "named kind"
        )))))
    }

    @Test
    fun `no argument warning for a method without the source`() {
        val (_, warnings) = withWarnings {
            build(
                """
                type Image {
                    thumbnail(size: Int!): String!
                }
                """,
                SourcelessImageResolver()
            )
        }

        assertEquals(warnings.filter { "has no parameter for its argument" in it }, listOf())
    }

    @Test
    fun `input objects of methods without the source are found from the right parameter`() {
        val data = query(
            """
            type Image {
                resized(spec: ResizeSpec!): String!
            }

            input ResizeSpec {
                width: Int!
                height: Int!
            }
            """,
            ResizingImageResolver()
        ) { imagesQuery("resized(spec: { width: 2, height: 3 })") }

        assertEquals(data, mapOf("folder" to mapOf("images" to listOf(mapOf("resized" to "2x3 resized")))))
    }

    private fun imagesQuery(field: String) =
        """
        query {
            folder {
                images {
                    $field
                }
            }
        }
        """

    private fun build(imageTypes: String, vararg resolvers: GraphQLResolver<*>?, options: SchemaParserOptions = SchemaParserOptions.defaultOptions()) =
        SchemaParser.newParser()
            .schemaString(folderSchema + imageTypes)
            .resolvers(listOf(FolderQuery(), FolderResolver()) + resolvers.filterNotNull())
            .options(options)
            .build()
            .makeExecutableSchema()

    private fun query(
        imageTypes: String,
        vararg resolvers: GraphQLResolver<*>?,
        options: SchemaParserOptions = SchemaParserOptions.defaultOptions(),
        query: () -> String
    ): Map<String, Any> = assertNoGraphQlErrors(GraphQL.newGraphQL(build(imageTypes, *resolvers, options = options)).build(), closure = query)

    data class AppContext(val user: String, val locale: String)

    class Folder(val supportedFileTypes: List<String>)

    interface Named {
        val name: String
    }

    class Image(override val name: String) : Named

    class FolderQuery : GraphQLQueryResolver {
        fun folder() = Folder(listOf("png", "jpg"))
    }

    class FolderResolver : GraphQLResolver<Folder> {
        fun images(folder: Folder): DataFetcherResult<List<Image>> = DataFetcherResult.newResult<List<Image>>()
            .data(listOf(Image("cat")))
            .localContext(folder.supportedFileTypes)
            .build()
    }

    class SourcelessImageResolver : GraphQLResolver<Image> {
        fun imageBlob(env: DataFetchingEnvironment) = "blob for ${env.getLocalContext<List<String>>()}"
        fun thumbnail(size: Int, env: DataFetchingEnvironment) = "${env.getLocalContext<List<String>>()?.first()}@$size"
        fun owner(context: GraphQLContext): String? = context.get("owner")
        fun locale(context: AppContext) = context.locale
        fun signature(context: AppContext, env: DataFetchingEnvironment) = "${env.field.name} by ${context.user}"
        suspend fun caption(env: DataFetchingEnvironment) = "caption of ${env.getSource<Image>()?.name}"
    }

    class ShadowedImageResolver : GraphQLResolver<Image> {
        fun getName(env: DataFetchingEnvironment) = "sourceless ${env.field.name}"
        fun title(env: DataFetchingEnvironment) = "sourceless ${env.field.name}"
        fun title(image: Image) = "with source ${image.name}"
        fun subtitle(image: Image) = "with source ${image.name}"
        fun subtitle(env: DataFetchingEnvironment) = "sourceless ${env.field.name}"
        fun label(env: DataFetchingEnvironment) = "sourceless ${env.field.name}"
        fun getLabel(image: Image) = "getLabel ${image.name}"
    }

    class FieldImageResolver : GraphQLResolver<Image> {
        val title = "resolver field"
        fun title(env: DataFetchingEnvironment) = "sourceless ${env.field.name}"
    }

    class Tags : HashMap<String, String>()

    class TagsQuery : GraphQLQueryResolver {
        fun tags() = Tags().apply { put("title", "map value") }
    }

    class TagsResolver : GraphQLResolver<Tags> {
        fun title(env: DataFetchingEnvironment) = "sourceless ${env.field.name}"
    }

    class FirstRootResolver : GraphQLQueryResolver {
        fun field(env: DataFetchingEnvironment) = env.field.name.length
    }

    class SecondRootResolver : GraphQLQueryResolver {
        fun field(env: DataFetchingEnvironment) = env.field.name.length + 1
    }

    class NoTrailingImageResolver : GraphQLResolver<Image> {
        fun preview() = "helper"
        fun reviews(image: Image, count: Int) = "$image $count"
    }

    class ExtensionImageResolver : GraphQLResolver<Image> {
        fun DataFetchingEnvironment.blob() = "blob ${field.name}"
    }

    class MisboundImageResolver : GraphQLResolver<Image> {
        fun thumbnail(context: GraphQLContext, env: DataFetchingEnvironment) = "thumbnail ${env.field.name} $context"
    }

    class MistypedSourceResolver : GraphQLResolver<Named> {
        fun title(image: Image, env: DataFetchingEnvironment) = "title ${image.name} ${env.field.name}"
    }

    class AnySourceResolver : GraphQLResolver<Image> {
        fun title(source: Any, env: DataFetchingEnvironment) = "title $source ${env.field.name}"
    }

    class HelperImageResolver : GraphQLResolver<Image> {
        fun preview(env: DataFetchingEnvironment) = "helper ${env.field.name}"
    }

    class SpecificImageResolver : GraphQLResolver<Image> {
        fun title(env: DataFetchingEnvironment) = "specific ${env.field.name}"
        fun label(env: DataFetchingEnvironment) = "specific ${env.field.name}"
    }

    class NamedResolver : GraphQLResolver<Named> {
        fun title(named: Named) = "named title ${named.name}"
        fun label(env: DataFetchingEnvironment) = "named ${env.field.name}"
        fun kind(env: DataFetchingEnvironment) = "named ${env.field.name}"
    }

    data class ResizeSpec(val width: Int, val height: Int)

    class ResizingImageResolver : GraphQLResolver<Image> {
        fun resized(spec: ResizeSpec, env: DataFetchingEnvironment) = "${spec.width}x${spec.height} ${env.field.name}"
    }
}
