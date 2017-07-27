package com.coxautodev.graphql.tools

import com.esotericsoftware.reflectasm.MethodAccess
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import graphql.language.NonNullType
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import java.lang.reflect.Method
import java.util.Optional

class MethodFieldResolverDataFetcher(val sourceResolver: SourceResolver, method: Method, val args: List<ArgumentPlaceholder>): DataFetcher<Any> {

    companion object {
        val mapper = ObjectMapper().registerModule(Jdk8Module()).registerKotlinModule()

        @JvmStatic internal fun create(fieldResolver: MethodFieldResolver): MethodFieldResolverDataFetcher {

            val args = mutableListOf<ArgumentPlaceholder>()

            // Add source argument if this is a resolver (but not a root resolver)
            if(fieldResolver.search.requiredFirstParameterType != null) {
                val expectedType = fieldResolver.search.requiredFirstParameterType

                args.add({ environment ->
                    val source = environment.getSource<Any>()
                    if (!(expectedType.isAssignableFrom(source.javaClass))) {
                        throw ResolverError("Source type (${source.javaClass.name}) is not expected type (${expectedType.name})!")
                    }

                    source
                })
            }

            // Add an argument for each argument defined in the GraphQL schema
            fieldResolver.field.inputValueDefinitions.forEachIndexed { index, definition ->

                val genericParameterType = fieldResolver.getJavaMethodParameterType(index) ?: throw ResolverError("Missing method type at position ${fieldResolver.getJavaMethodParameterIndex(index)}, this is most likely a bug with graphql-java-tools")

                val isNonNull = definition.type is NonNullType
                val isOptional = fieldResolver.genericType.getRawClass(genericParameterType) == Optional::class.java

                val typeReference = object: TypeReference<Any>() {
                    override fun getType() = genericParameterType
                }

                args.add({ environment ->
                    val value = environment.arguments[definition.name] ?: if(isNonNull) {
                        throw ResolverError("Missing required argument with name '${definition.name}', this is most likely a bug with graphql-java-tools")
                    } else {
                        null
                    }

                    if(value == null && isOptional) {
                        return@add Optional.empty<Any>()
                    }

                    return@add mapper.convertValue(value, typeReference)
                })
            }

            // Add DataFetchingEnvironment argument
            if(fieldResolver.dataFetchingEnvironment) {
                args.add({ environment -> environment })
            }

            // Add source resolver depending on whether or not this is a resolver method
            val sourceResolver: SourceResolver = if(fieldResolver.resolverInfo is NormalResolverInfo) ({ fieldResolver.resolverInfo.resolver }) else ({ environment ->
                val source = environment.getSource<Any>()

                if(!fieldResolver.genericType.isAssignableFrom(source.javaClass)) {
                    throw ResolverError("Expected source object to be an instance of '${fieldResolver.genericType.getRawClass().name}' but instead got '${source.javaClass.name}'")
                }

                source
            })

            return MethodFieldResolverDataFetcher(sourceResolver, fieldResolver.method, args)
        }
    }

    // Convert to reflactasm reflection
    val methodAccess = MethodAccess.get(method.declaringClass)!!
    val methodIndex = methodAccess.getIndex(method.name, *method.parameterTypes)

    override fun get(environment: DataFetchingEnvironment): Any? {
        val source = sourceResolver(environment)
        val args = this.args.map { it(environment) }.toTypedArray()
        val result = methodAccess.invoke(source, methodIndex, *args)
        return if(result is Optional<*>) result.orElse(null) else result
    }
}

class ResolverError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
typealias SourceResolver = (DataFetchingEnvironment) -> Any
typealias ArgumentPlaceholder = (DataFetchingEnvironment) -> Any?