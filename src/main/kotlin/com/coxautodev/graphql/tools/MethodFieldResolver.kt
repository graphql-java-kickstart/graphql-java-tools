package com.coxautodev.graphql.tools

import com.esotericsoftware.reflectasm.MethodAccess
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import graphql.language.FieldDefinition
import graphql.language.NonNullType
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import java.lang.reflect.Method
import java.util.Optional

/**
 * @author Andrew Potter
 */
internal class MethodFieldResolver(field: FieldDefinition, search: FieldResolverScanner.Search, options: SchemaParserOptions, val method: Method): FieldResolver(field, search, method.declaringClass, options) {

    val dataFetchingEnvironment = method.parameterCount == (field.inputValueDefinitions.size + getIndexOffset() + 1)

    override fun createDataFetcher(): DataFetcher<*> {
        val args = mutableListOf<ArgumentPlaceholder>()

        // Add source argument if this is a resolver (but not a root resolver)
        if(this.search.requiredFirstParameterType != null) {
            val expectedType = this.search.requiredFirstParameterType

            args.add({ environment ->
                val source = environment.getSource<Any>()
                if (!(expectedType.isAssignableFrom(source.javaClass))) {
                    throw ResolverError("Source type (${source.javaClass.name}) is not expected type (${expectedType.name})!")
                }

                source
            })
        }

        // Add an argument for each argument defined in the GraphQL schema
        this.field.inputValueDefinitions.forEachIndexed { index, definition ->

            val genericParameterType = this.getJavaMethodParameterType(index) ?: throw ResolverError("Missing method type at position ${this.getJavaMethodParameterIndex(index)}, this is most likely a bug with graphql-java-tools")

            val isNonNull = definition.type is NonNullType
            val isOptional = this.genericType.getRawClass(genericParameterType) == Optional::class.java

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

                return@add MethodFieldResolverDataFetcher.mapper.convertValue(value, typeReference)
            })
        }

        // Add DataFetchingEnvironment argument
        if(this.dataFetchingEnvironment) {
            args.add({ environment -> environment })
        }

        return MethodFieldResolverDataFetcher(getSourceResolver(), this.method, args)
    }

    override fun scanForMatches(): List<TypeClassMatcher.PotentialMatch> {
        val returnValueMatch = TypeClassMatcher.PotentialMatch.returnValue(field.type, method.genericReturnType, genericType, SchemaClassScanner.ReturnValueReference(method))

        return field.inputValueDefinitions.mapIndexed { i, inputDefinition ->
            TypeClassMatcher.PotentialMatch.parameterType(inputDefinition.type, getJavaMethodParameterType(i)!!, genericType, SchemaClassScanner.MethodParameterReference(method, i))
        } + listOf(returnValueMatch)
    }

    private fun getIndexOffset() = if(resolverInfo is NormalResolverInfo) 1 else 0
    fun getJavaMethodParameterIndex(index: Int) = index + getIndexOffset()

    fun getJavaMethodParameterType(index: Int): JavaType? {
        val methodIndex = getJavaMethodParameterIndex(index)
        val parameters = method.parameterTypes
        if(parameters.size > methodIndex) {
            return method.genericParameterTypes[getJavaMethodParameterIndex(index)]
        } else {
            return null
        }
    }

    override fun toString() = "MethodFieldResolver{method=$method}"
}

class MethodFieldResolverDataFetcher(val sourceResolver: SourceResolver, method: Method, private val args: List<ArgumentPlaceholder>): DataFetcher<Any> {
    companion object {
        val mapper = ObjectMapper().registerModule(Jdk8Module()).registerKotlinModule()
    }

    // Convert to reflactasm reflection
    private val methodAccess = MethodAccess.get(method.declaringClass)!!
    private val methodIndex = methodAccess.getIndex(method.name, *method.parameterTypes)

    override fun get(environment: DataFetchingEnvironment): Any? {
        val source = sourceResolver(environment)
        val args = this.args.map { it(environment) }.toTypedArray()
        val result = methodAccess.invoke(source, methodIndex, *args)
        return if(result != null && result is Optional<*>) result.orElse(null) else result
    }
}

internal typealias ArgumentPlaceholder = (DataFetchingEnvironment) -> Any?

