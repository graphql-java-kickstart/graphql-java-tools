package graphql.kickstart.tools.compat.scenarios

import graphql.ExecutionInput
import graphql.GraphQLContext
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.GraphQLResolver
import graphql.kickstart.tools.SchemaParserBuilder
import graphql.kickstart.tools.SchemaParserOptions
import graphql.schema.DataFetchingEnvironment

object ContextAndEnvironment : Scenario {
    override val name = "context-and-environment"

    override val files = listOf("compat/scenarios/context-and-environment/schema.graphqls")
    override val resolvers = listOf(ContextAndEnvironmentQuery(), UserResolver())

    override fun configure(builder: SchemaParserBuilder) = builder
        .options(SchemaParserOptions.newOptions().contextClass(AppContext::class).build())

    override fun input(builder: ExecutionInput.Builder): ExecutionInput.Builder =
        builder.graphQLContext(mapOf(AppContext::class.java to AppContext("oryan", "en-CA")))
}

object ContextAndEnvironmentInvalid : Scenario {
    override val name = "context-and-environment-invalid"

    override val files = listOf("compat/scenarios/context-and-environment-invalid/schema.graphqls")
    override val resolvers = listOf(ContextAndEnvironmentInvalidQuery())

    override fun configure(builder: SchemaParserBuilder) = builder
        .options(SchemaParserOptions.newOptions().contextClass(AppContext::class).build())
}

data class AppContext(val user: String, val locale: String)

class AppUser(val name: String)

class ContextAndEnvironmentQuery : GraphQLQueryResolver {
    fun greeting(name: String, context: AppContext, env: DataFetchingEnvironment) =
        "${env.field.name}: hi $name, from ${context.user}"

    fun farewell(name: String, env: DataFetchingEnvironment, context: AppContext) =
        "${env.field.name}: bye $name, from ${context.user}"

    fun user(context: AppContext) = AppUser(context.user)

    fun environmentOnly(env: DataFetchingEnvironment) = env.field.name

    fun contextOnly(context: AppContext) = context.locale
}

class UserResolver : GraphQLResolver<AppUser> {
    fun locale(user: AppUser, context: GraphQLContext, env: DataFetchingEnvironment) =
        "${env.field.name} of ${user.name}: ${context.get<AppContext>(AppContext::class.java)?.locale}"
}

class ContextAndEnvironmentInvalidQuery : GraphQLQueryResolver {
    fun greeting(name: String, context: AppContext, extra: String) = "hi $name"
}
