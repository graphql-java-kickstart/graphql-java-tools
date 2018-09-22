package com.coxautodev.graphql.tools

import com.coxautodev.graphql.tools.SchemaParserOptions.GenericWrapper
import com.esotericsoftware.reflectasm.MethodAccess
import com.fasterxml.jackson.core.type.TypeReference
import graphql.execution.batched.Batched
import graphql.language.FieldDefinition
import graphql.language.NonNullType
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import java.lang.reflect.Method
import java.util.*

/**
 * @author Andrew Potter
 */
internal class MethodFieldResolver(field: FieldDefinition, search: FieldResolverScanner.Search, options: SchemaParserOptions, val method: Method) : FieldResolver(field, search, options, method.declaringClass) {

    companion object {
        fun isBatched(method: Method, search: FieldResolverScanner.Search): Boolean {
            if (method.getAnnotation(Batched::class.java) != null) {
                if (!search.allowBatched) {
                    throw ResolverError("The @Batched annotation is only allowed on non-root resolver methods, but it was found on ${search.type.name}#${method.name}!")
                }

                return true
            }

            return false
        }
    }

    private val additionalLastArgument = method.parameterCount == (field.inputValueDefinitions.size + getIndexOffset() + 1)

    override fun createDataFetcher(): DataFetcher<*> {
        val batched = isBatched(method, search)
        val args = mutableListOf<ArgumentPlaceholder>()
        val mapper = options.objectMapperProvider.provide(field)

        // Add source argument if this is a resolver (but not a root resolver)
        if (this.search.requiredFirstParameterType != null) {
            val expectedType = if (batched) Iterable::class.java else this.search.requiredFirstParameterType

            args.add { environment ->
                val source = environment.getSource<Any>()
                if (!(expectedType.isAssignableFrom(source.javaClass))) {
                    throw ResolverError("Source type (${source.javaClass.name}) is not expected type (${expectedType.name})!")
                }

                source
            }
        }

        // Add an argument for each argument defined in the GraphQL schema
        this.field.inputValueDefinitions.forEachIndexed { index, definition ->

            val genericParameterType = this.getJavaMethodParameterType(index)
                    ?: throw ResolverError("Missing method type at position ${this.getJavaMethodParameterIndex(index)}, this is most likely a bug with graphql-java-tools")

            val isNonNull = definition.type is NonNullType
            val isOptional = this.genericType.getRawClass(genericParameterType) == Optional::class.java

            val typeReference = object : TypeReference<Any>() {
                override fun getType() = genericParameterType
            }

            args.add { environment ->
                val value = environment.arguments[definition.name] ?: if (isNonNull) {
                    throw ResolverError("Missing required argument with name '${definition.name}', this is most likely a bug with graphql-java-tools")
                } else {
                    null
                }

                if (value == null && isOptional) {
                    return@add Optional.empty<Any>()
                }

                return@add mapper.convertValue(value, typeReference)
            }
        }

        // Add DataFetchingEnvironment/Context argument
        if (this.additionalLastArgument) {
            val lastArgumentType = this.method.parameterTypes.last()
            when (lastArgumentType) {
                null -> throw ResolverError("Expected at least one argument but got none, this is most likely a bug with graphql-java-tools")
                options.contextClass -> args.add { environment -> environment.getContext() }
                else -> args.add { environment -> environment }
            }
        }

        return if (batched) {
            BatchedMethodFieldResolverDataFetcher(getSourceResolver(), this.method, args, options)
        } else {
            MethodFieldResolverDataFetcher(getSourceResolver(), this.method, args, options)
        }
    }

    override fun scanForMatches(): List<TypeClassMatcher.PotentialMatch> {
        val batched = isBatched(method, search)
        val returnValueMatch = TypeClassMatcher.PotentialMatch.returnValue(field.type, method.genericReturnType, genericType, SchemaClassScanner.ReturnValueReference(method), batched)

        return field.inputValueDefinitions.mapIndexed { i, inputDefinition ->
            TypeClassMatcher.PotentialMatch.parameterType(inputDefinition.type, getJavaMethodParameterType(i)!!, genericType, SchemaClassScanner.MethodParameterReference(method, i), batched)
        } + listOf(returnValueMatch)
    }

    private fun getIndexOffset(): Int {
        return if (resolverInfo is DataClassTypeResolverInfo && !method.declaringClass.isAssignableFrom(resolverInfo.dataClassType)) {
            1
        } else {
            0
        }
    }

    private fun getJavaMethodParameterIndex(index: Int) = index + getIndexOffset()

    private fun getJavaMethodParameterType(index: Int): JavaType? {
        val methodIndex = getJavaMethodParameterIndex(index)
        val parameters = method.parameterTypes

        return if (parameters.size > methodIndex) {
            method.genericParameterTypes[getJavaMethodParameterIndex(index)]
        } else {
            null
        }
    }

    override fun toString() = "MethodFieldResolver{method=$method}"
}

open class MethodFieldResolverDataFetcher(private val sourceResolver: SourceResolver, method: Method, private val args: List<ArgumentPlaceholder>, private val options: SchemaParserOptions) : DataFetcher<Any> {

    // Convert to reflactasm reflection
    private val methodAccess = MethodAccess.get(method.declaringClass)!!
    private val methodIndex = methodAccess.getIndex(method.name, *method.parameterTypes)

    private class CompareGenericWrappers {
        companion object : Comparator<GenericWrapper> {
            override fun compare(w1: GenericWrapper, w2: GenericWrapper): Int = when {
                w1.type.isAssignableFrom(w2.type) -> 1
                else -> -1
            }
        }
    }

    override fun get(environment: DataFetchingEnvironment): Any? {
        val source = sourceResolver(environment)
        val args = this.args.map { it(environment) }.toTypedArray()
        val result = methodAccess.invoke(source, methodIndex, *args)
        return if (result == null) {
            result
        } else {
            val wrapper = options.genericWrappers
                    .asSequence()
                    .filter { it.type.isInstance(result) }
                    .sortedWith(CompareGenericWrappers)
                    .firstOrNull()
            if (wrapper == null) {
                result
            } else {
                wrapper.transformer.invoke(result, environment)
            }
        }
    }

    /**
     * Function that return the object used to fetch the data
     * It can be a DataFetcher or an entity
     */
    @Suppress("unused")
    open fun getWrappedFetchingObject(environment: DataFetchingEnvironment): Any {
        return sourceResolver(environment)
    }
}

class BatchedMethodFieldResolverDataFetcher(sourceResolver: SourceResolver, method: Method, args: List<ArgumentPlaceholder>, options: SchemaParserOptions) : MethodFieldResolverDataFetcher(sourceResolver, method, args, options) {
    @Batched
    override fun get(environment: DataFetchingEnvironment) = super.get(environment)
}

internal typealias ArgumentPlaceholder = (DataFetchingEnvironment) -> Any?

