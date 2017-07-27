package com.coxautodev.graphql.tools

import org.apache.commons.lang3.reflect.TypeUtils
import java.lang.reflect.ParameterizedType

internal abstract class ResolverInfo {
    abstract fun getFieldSearches(): List<FieldResolverScanner.Search>
}

internal class NormalResolverInfo(resolver: GraphQLResolver<*>): ResolverInfo() {
    val resolverType = resolver.javaClass
    val dataClassType = findDataClass()

    private fun findDataClass(): Class<*> {
        // Grab the parent interface with type GraphQLResolver from our resolver and get its first type argument.
        val interfaceType = GenericType(resolverType).getGenericInterface(GraphQLResolver::class.java)
        if(interfaceType == null || interfaceType !is ParameterizedType) {
            error("${GraphQLResolver::class.java.simpleName} interface was not parameterized for: ${resolverType.name}")
        }

        val type = TypeUtils.determineTypeArguments(resolverType, interfaceType)[GraphQLResolver::class.java.typeParameters[0]]

        if(type == null || type !is Class<*>) {
            throw ResolverError("Unable to determine data class for resolver '${resolverType.name}' from generic interface!  This is most likely a bug with graphql-java-tools.")
        }

        if(type == Void::class.java) {
            throw ResolverError("Resolvers may not have ${Void::class.java.name} as their type, use a real type or use a root resolver interface.")
        }

        return type
    }

    override fun getFieldSearches(): List<FieldResolverScanner.Search> {
        return listOf(
            FieldResolverScanner.Search(resolverType, true, false, dataClassType),
            FieldResolverScanner.Search(dataClassType, false, false)
        )
    }
}

internal class RootResolverInfo(val resolvers: List<GraphQLRootResolver>): ResolverInfo() {
    override fun getFieldSearches(): List<FieldResolverScanner.Search> {
        return resolvers.map { FieldResolverScanner.Search(it.javaClass, true, true) }
    }
}

internal class DataClassResolverInfo(val dataClass: Class<*>): ResolverInfo() {
    override fun getFieldSearches(): List<FieldResolverScanner.Search> {
        return listOf(FieldResolverScanner.Search(dataClass, false, false))
    }
}
