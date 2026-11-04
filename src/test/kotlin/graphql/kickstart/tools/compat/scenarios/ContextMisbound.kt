package graphql.kickstart.tools.compat.scenarios

import graphql.ExecutionInput
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.GraphQLResolver
import graphql.kickstart.tools.SchemaParserBuilder
import graphql.kickstart.tools.SchemaParserOptions
import graphql.schema.DataFetchingEnvironment

object ContextMisbound : Scenario {
    override val name = "context-misbound"

    override val files = listOf("compat/scenarios/context-misbound/schema.graphqls")
    override val resolvers = listOf(ContextMisboundQuery(), MisboundUserResolver())

    override fun configure(builder: SchemaParserBuilder) = builder
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
