package scenarios

import graphql.ExecutionInput
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.GraphQLResolver
import graphql.kickstart.tools.SchemaParser
import graphql.kickstart.tools.SchemaParserOptions
import graphql.schema.DataFetchingEnvironment

/** Resolver methods whose environment or context parameter takes the place of a schema argument. */
object ContextMisbound : Scenario {
    override val name = "context-misbound"

    override fun parser() = SchemaParser.newParser()
        .file("scenarios/context-misbound/schema.graphqls")
        .resolvers(ContextMisboundQuery(), MisboundUserResolver())
        .options(SchemaParserOptions.newOptions().contextClass(AppContext::class).build())

    override fun input(builder: ExecutionInput.Builder): ExecutionInput.Builder =
        builder.graphQLContext(mapOf(AppContext::class.java to AppContext("oryan", "en-CA")))
}

class ContextMisboundQuery : GraphQLQueryResolver {
    fun users(env: DataFetchingEnvironment) = listOf(AppUser(env.field.name))

    fun user() = AppUser("ada")

    fun contextArgument(context: AppContext) = context.user

    fun greeting(name: String, env: DataFetchingEnvironment) = "${env.field.name}: hi $name"
}

class MisboundUserResolver : GraphQLResolver<AppUser> {
    fun thumbnail(user: AppUser, env: DataFetchingEnvironment) = "${env.field.name} of ${user.name}"
}
