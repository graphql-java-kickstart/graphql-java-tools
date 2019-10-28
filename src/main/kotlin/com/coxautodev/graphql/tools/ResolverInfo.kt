package com.coxautodev.graphql.tools

import org.apache.commons.lang3.reflect.TypeUtils
import java.lang.reflect.ParameterizedType

internal abstract class ResolverInfo {
    abstract fun getFieldSearches(): List<FieldResolverScanner.Search>

    fun getRealResolverClass(resolver: GraphQLResolver<*>, options: SchemaParserOptions) =
            options.proxyHandlers.find { it.canHandle(resolver) }?.getTargetClass(resolver) ?: resolver.javaClass
}

internal interface DataClassTypeResolverInfo {
    val dataClassType: Class<out Any>
}

internal class NormalResolverInfo(val resolver: GraphQLResolver<*>, private val options: SchemaParserOptions) : DataClassTypeResolverInfo, ResolverInfo() {
    val resolverType = getRealResolverClass(resolver, options)
    override val dataClassType = findDataClass()

    private fun findDataClass(): Class<out Any> {

        val type = TypeUtils.getTypeArguments(resolverType, GraphQLResolver::class.java)[GraphQLResolver::class.java.typeParameters[0]]

        if (type == null || type !is Class<*>) {
            throw ResolverError("Unable to determine data class for resolver '${resolverType.name}' from generic interface! This is most likely a bug with graphql-java-tools.")
        }

        if (type == Void::class.java) {
            throw ResolverError("Resolvers may not have ${Void::class.java.name} as their type, use a real type or use a root resolver interface.")
        }

        return type
    }

    override fun getFieldSearches(): List<FieldResolverScanner.Search> {
        return listOf(
                FieldResolverScanner.Search(resolverType, this, resolver, dataClassType, true),
                FieldResolverScanner.Search(dataClassType, this, null)
        )
    }
}

internal class MultiResolverInfo(val resolverInfoList: List<NormalResolverInfo>) : DataClassTypeResolverInfo, ResolverInfo() {
    override val dataClassType = findDataClass()

    /**
     * Checks if all `ResolverInfo` instances are related to the same data type
     */
    private fun findDataClass(): Class<out Any> {
        val dataClass = resolverInfoList.asSequence().map { it.dataClassType }.distinct().singleOrNull()

        if (dataClass == null) {
            throw ResolverError("Resolvers may not use the same type.")
        } else {
            return dataClass
        }
    }

    override fun getFieldSearches(): List<FieldResolverScanner.Search> {
        return resolverInfoList
                .asSequence()
                .map { FieldResolverScanner.Search(it.resolverType, this, it.resolver, dataClassType, true) }
                .plus(FieldResolverScanner.Search(dataClassType, this, null))
                .toList()
    }
}

internal class RootResolverInfo(val resolvers: List<GraphQLRootResolver>, private val options: SchemaParserOptions) : ResolverInfo() {
    override fun getFieldSearches() =
            resolvers.map { FieldResolverScanner.Search(getRealResolverClass(it, options), this, it) }
}

internal class DataClassResolverInfo(private val dataClass: JavaType) : ResolverInfo() {
    override fun getFieldSearches() =
            listOf(FieldResolverScanner.Search(dataClass, this, null))
}

internal class MissingResolverInfo : ResolverInfo() {
    override fun getFieldSearches(): List<FieldResolverScanner.Search> = listOf()
}

class ResolverError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
