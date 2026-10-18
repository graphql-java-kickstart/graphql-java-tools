package graphql.kickstart.tools

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.Scalars
import graphql.schema.*
import graphql.schema.GraphQLTypeUtil.isNonNull
import graphql.schema.GraphQLTypeUtil.unwrapAll
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs one operation per root field that selects every field reachable from it, and fails on any error. A
 * subscription has to send an event, and that event's errors count too.
 *
 * Arguments without a default get a made-up value. Fields ("Type.field") and arguments ("Type.field(arg:)") named in
 * [except] are left out. So is a field with a required argument that's left out or is a custom scalar, which has no
 * value to make up. [input] adds to every operation, e.g. a context or a data loader registry.
 */
@JvmOverloads
fun GraphQLSchema.assertEveryFieldResolves(except: Set<String> = setOf(), input: ExecutionInput.Builder.() -> Unit = {}): GraphQLSchema {
    val gql = GraphQL.newGraphQL(this).build()
    val query = EveryFieldQuery(this, except)
    val roots = mapOf("query" to queryType, "mutation" to mutationType, "subscription" to subscriptionType)
    val errors = roots.flatMap { (operation, root) ->
        if (root == null) return@flatMap listOf()
        query.fields(root).flatMap { field ->
            val document = "$operation { $field }"
            execute(gql, ExecutionInput.newExecutionInput(document).apply(input).build()).map { "$document\n  $it" }
        }
    }
    if (errors.isNotEmpty()) {
        throw AssertionError("Querying every field returned errors:\n${errors.joinToString("\n")}")
    }
    return this
}

private const val MAX_DEPTH = 6

private class EveryFieldQuery(private val schema: GraphQLSchema, private val except: Set<String>) {
    private val visibility = schema.codeRegistry.fieldVisibility

    fun fields(type: GraphQLFieldsContainer, path: List<String> = listOf()): List<String> =
        visibility.getFieldDefinitions(type)
            .filter { "${type.name}.${it.name}" !in except }
            .mapNotNull { field(type, it, path + type.name) }

    private fun field(parent: GraphQLFieldsContainer, field: GraphQLFieldDefinition, path: List<String>): String? {
        val type = unwrapAll(field.type)
        // a type can repeat once on a path, so a field that refers back to its own type still runs
        if (type is GraphQLCompositeType && (path.size >= MAX_DEPTH || repeated(type, path))) {
            return null
        }
        val arguments = field.arguments.filter { !it.hasSetDefaultValue() }.mapNotNull { argument ->
            val value = if ("${parent.name}.${field.name}(${argument.name}:)" in except) null else value(argument.type, setOf())
            when {
                value != null -> "${argument.name}: $value"
                isNonNull(argument.type) -> return null
                else -> null
            }
        }
        val argumentList = if (arguments.isEmpty()) "" else arguments.joinToString(", ", "(", ")")
        // aliased so fields with the same name but different types can sit side by side in fragments
        return "${parent.name}_${field.name}: ${field.name}$argumentList ${selection(type, path)}"
    }

    private fun selection(type: GraphQLNamedType, path: List<String>): String = when (type) {
        is GraphQLObjectType -> "{ __typename ${fields(type, path).joinToString(" ")} }"
        is GraphQLInterfaceType -> fragments(schema.getImplementations(type) ?: listOf(), path)
        is GraphQLUnionType -> fragments(type.types, path)
        else -> ""
    }

    private fun fragments(types: List<GraphQLNamedType>, path: List<String>) =
        "{ __typename ${types.filter { !repeated(it, path) }.joinToString(" ") { "... on ${it.name} ${selection(it, path)}" }} }"

    private fun repeated(type: GraphQLNamedType, path: List<String>) = path.count { it == type.name } >= 2

    private fun value(type: GraphQLInputType, path: Set<String>): String? = when (type) {
        is GraphQLNonNull -> value(type.wrappedType as GraphQLInputType, path)
        is GraphQLList -> value(type.wrappedType as GraphQLInputType, path)?.let { "[$it]" }
        is GraphQLEnumType -> type.values.first().name
        is GraphQLInputObjectType -> inputObject(type, path)
        Scalars.GraphQLString -> "\"a\""
        Scalars.GraphQLID -> "\"1\""
        Scalars.GraphQLInt -> "1"
        Scalars.GraphQLFloat -> "1.5"
        Scalars.GraphQLBoolean -> "true"
        else -> null
    }

    private fun inputObject(type: GraphQLInputObjectType, path: Set<String>): String? {
        if (type.name in path) return null
        val fields = type.fieldDefinitions.filter { !it.hasSetDefaultValue() }.mapNotNull { field ->
            val value = value(field.type, path + type.name)
            when {
                value != null -> "${field.name}: $value"
                isNonNull(field.type) -> return null
                else -> null
            }
        }
        // a @oneOf input takes exactly one field
        if (type.isOneOf) return fields.firstOrNull()?.let { "{ $it }" }
        return fields.joinToString(", ", "{ ", " }")
    }
}

private fun execute(gql: GraphQL, input: ExecutionInput): List<String> {
    // a resolver that never completes fails here instead of hanging the build
    val result = try {
        gql.executeAsync(input).get(5, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        return listOf("Timed out")
    } catch (e: ExecutionException) {
        // graphql-java turns exceptions into GraphQL errors, but not an Error such as a failed assert
        return listOf("Failed: ${e.cause}")
    }
    val events = result.getData<Any>() as? Publisher<*> ?: return result.errors.map { it.message }
    return result.errors.map { it.message } + firstEventErrors(events)
}

private fun firstEventErrors(events: Publisher<*>): List<String> {
    val first = CompletableFuture<ExecutionResult>()
    var subscription: Subscription? = null
    events.subscribe(object : Subscriber<Any?> {
        override fun onSubscribe(s: Subscription) {
            subscription = s
            s.request(1)
        }

        override fun onNext(event: Any?) {
            first.complete(event as ExecutionResult)
        }

        override fun onError(t: Throwable) {
            first.completeExceptionally(t)
        }

        override fun onComplete() {
            first.completeExceptionally(IllegalStateException("completed without an event"))
        }
    })
    return try {
        first.get(5, TimeUnit.SECONDS).errors.map { it.message }
    } catch (e: TimeoutException) {
        listOf("Subscription sent no event")
    } catch (e: ExecutionException) {
        listOf("Subscription failed: ${e.cause}")
    } finally {
        subscription?.cancel()
    }
}
