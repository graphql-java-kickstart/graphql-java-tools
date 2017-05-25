package com.coxautodev.graphql.tools

import com.esotericsoftware.reflectasm.MethodAccess
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import graphql.language.FieldDefinition
import graphql.language.NonNullType
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import java.lang.reflect.Method
import java.lang.reflect.Type

class ResolverDataFetcher(val sourceResolver: SourceResolver, method: Method, val args: List<ArgumentPlaceholder>): DataFetcher<Any> {

    companion object {
        val mapper = ObjectMapper().registerKotlinModule()

        @JvmStatic fun create(resolver: Resolver, field: FieldDefinition): ResolverDataFetcher {

            val method = resolver.getMethod(field)
            val args = mutableListOf<ArgumentPlaceholder>()

            // Add source argument if this is a resolver (but not a root resolver)
            if(method.sourceArgument) {
                val expectedType = resolver.dataClassType!! // We've already checked this when setting shouldPassSource
                args.add({ environment ->
                    val source = environment.getSource<Any>()
                    if (!(expectedType.isAssignableFrom(source.javaClass))) {
                        throw ResolverError("Source type (${source.javaClass.name}) is not expected type (${expectedType.name})!")
                    }

                    source
                })
            }

            // Add an argument for each argument defined in the GraphQL schema
            field.inputValueDefinitions.forEachIndexed { index, definition ->
                args.add({ environment ->
                    val value = environment.arguments[definition.name] ?: if(definition.type is NonNullType) {
                        throw ResolverError("Missing required argument with name '${definition.name}', this is most likely a bug with graphql-java-tools")
                    } else {
                        return@add null
                    }

                    // Convert to specific type if actual argument value is Map<?, ?> and method parameter type is not Map<?, ?>
                    if (value is Map<*, *>) {
                        val type = method.getJavaMethodParameterType(index) ?: throw ResolverError("Missing method type at position ${method.getJavaMethodParameterIndex(index)}, this is most likely a bug with graphql-java-tools")
                        if (type is Class<*> && Map::class.java.isAssignableFrom(type)) {
                            return@add value
                        }

                        return@add mapper.convertValue(value, object: TypeReference<Any>() {
                            override fun getType() = type
                        })
                    }

                    value
                })
            }

            // Add DataFetchingEnvironment argument
            if(method.dataFetchingEnvironment) {
                args.add({ environment -> environment })
            }

            // Add source resolver depending on whether or not this is a resolver method
            val sourceResolver: SourceResolver = if(method.resolverMethod) ({ resolver.resolver }) else ({ environment ->
                val source = environment.getSource<Any>()

                if(!method.methodClass.isAssignableFrom(source.javaClass)) {
                    throw ResolverError("Expected source object to be an instance of '${method.methodClass.name}' but instead got '${source.javaClass.name}'")
                }

                source
            })

            return ResolverDataFetcher(sourceResolver, method.javaMethod, args)
        }
    }

    // Convert to reflactasm reflection
    val methodAccess = MethodAccess.get(method.declaringClass)!!
    val methodIndex = methodAccess.getIndex(method.name, *method.parameterTypes)

    override fun get(environment: DataFetchingEnvironment): Any? {
        val source = sourceResolver(environment)
        val args = this.args.map { it(environment) }.toTypedArray()
        return methodAccess.invoke(source, methodIndex, *args)
    }
}

class ResolverError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
typealias SourceResolver = (DataFetchingEnvironment) -> Any
typealias ArgumentPlaceholder = (DataFetchingEnvironment) -> Any?
