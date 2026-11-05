package graphql.kickstart.tools.compat.scenarios

import graphql.ExecutionInput
import graphql.GraphQLContext
import graphql.execution.DataFetcherResult
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.GraphQLResolver
import graphql.kickstart.tools.SchemaParserBuilder
import graphql.kickstart.tools.SchemaParserOptions
import graphql.schema.DataFetchingEnvironment

object Sourceless : Scenario {
    override val name = "sourceless"

    override val files = listOf("compat/scenarios/sourceless/schema.graphqls")
    override val resolvers = listOf(SourcelessQuery(), SourcelessFolderResolver(), SourcelessImageResolver())

    override fun configure(builder: SchemaParserBuilder) = builder
        .options(SchemaParserOptions.newOptions().contextClass(AppContext::class).build())

    override fun input(builder: ExecutionInput.Builder): ExecutionInput.Builder =
        builder.graphQLContext(mapOf(AppContext::class.java to AppContext("oryan", "en-CA"), "owner" to "ada"))
}

object SourcelessShadowed : Scenario {
    override val name = "sourceless-shadowed"

    override val files = listOf("compat/scenarios/sourceless-shadowed/schema.graphqls")
    override val resolvers = listOf(SourcelessQuery(), SourcelessFolderResolver(), ShadowedImageResolver())
}

object SourcelessUnimplemented : Scenario {
    override val name = "sourceless-unimplemented"

    override val files = listOf("compat/scenarios/sourceless-unimplemented/schema.graphqls")
    override val resolvers = listOf(SourcelessQuery(), SourcelessFolderResolver(), HelperImageResolver())

    override fun configure(builder: SchemaParserBuilder) = builder
        .options(SchemaParserOptions.newOptions().allowUnimplementedResolvers(true).build())
}

object SourcelessMisbound : Scenario {
    override val name = "sourceless-misbound"

    override val files = listOf("compat/scenarios/sourceless-misbound/schema.graphqls")
    override val resolvers = listOf(SourcelessQuery(), SourcelessFolderResolver(), MisboundImageResolver())
}

class SourcelessFolder(val supportedFileTypes: List<String>)

class SourcelessImage(val name: String)

class SourcelessQuery : GraphQLQueryResolver {
    fun folder() = SourcelessFolder(listOf("png", "jpg"))
}

class SourcelessFolderResolver : GraphQLResolver<SourcelessFolder> {
    fun images(folder: SourcelessFolder): DataFetcherResult<List<SourcelessImage>> = DataFetcherResult.newResult<List<SourcelessImage>>()
        .data(listOf(SourcelessImage("cat"), SourcelessImage("dog")))
        .localContext(folder.supportedFileTypes)
        .build()
}

class SourcelessImageResolver : GraphQLResolver<SourcelessImage> {
    fun imageBlob(env: DataFetchingEnvironment) = "blob for ${env.getLocalContext<List<String>>()}"

    fun thumbnail(size: Int, env: DataFetchingEnvironment) = "${env.getLocalContext<List<String>>()?.first()}@$size"

    fun owner(context: GraphQLContext): String? = context.get("owner")

    fun locale(context: AppContext) = context.locale

    fun signature(context: AppContext, env: DataFetchingEnvironment) = "${env.field.name} by ${context.user}"

    suspend fun caption(env: DataFetchingEnvironment) = "caption of ${env.getSource<SourcelessImage>()?.name}"
}

class ShadowedImageResolver : GraphQLResolver<SourcelessImage> {
    fun getName(env: DataFetchingEnvironment) = "sourceless ${env.field.name}"

    fun title(image: SourcelessImage) = "with source ${image.name}"

    fun title(env: DataFetchingEnvironment) = "sourceless ${env.field.name}"
}

class HelperImageResolver : GraphQLResolver<SourcelessImage> {
    fun imageBlob(image: SourcelessImage) = "blob of ${image.name}"

    fun preview(env: DataFetchingEnvironment) = "helper ${env.field.name}"
}

class MisboundImageResolver : GraphQLResolver<SourcelessImage> {
    fun thumbnail(context: GraphQLContext, env: DataFetchingEnvironment) = "thumbnail ${env.field.name}"
}
