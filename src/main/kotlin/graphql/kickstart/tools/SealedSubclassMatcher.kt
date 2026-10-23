package graphql.kickstart.tools

import graphql.kickstart.tools.ScanDictionary.SealedSubclassReference
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.unwrap
import graphql.kickstart.tools.util.withExtensions
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.TypeDefinition
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
import org.apache.commons.lang3.reflect.TypeUtils
import java.lang.reflect.ParameterizedType

internal class SealedSubclassMatcher(
    private val dictionary: Map<TypeDefinition<*>, ScanDictionary.EntryView>,
    private val typeExtensions: TypeExtensions,
) {
    fun sealedSubclassHint(type: TypeDefinition<*>): String {
        if (type !is ObjectTypeDefinition) {
            return ""
        }

        val sealedTypes = sealedTypesOf(type)
        if (sealedTypes.isEmpty()) {
            return ""
        }

        val sealedTypeNames = sealedTypes.joinToString(" or ") { it.unwrap().name }
        val matches = sealedSubclassMatches(type)
        return if (matches.isEmpty()) {
            "  No subclass of sealed type $sealedTypeNames is named '${type.name}'."
        } else {
            "  More than one subclass of sealed type $sealedTypeNames is named '${type.name}': ${matches.joinToString { it.javaType.unwrap().name }}."
        }
    }

    fun sealedSubclassMatches(type: ObjectTypeDefinition): List<TypeClassMatcher.ValidMatch> =
        sealedTypesOf(type)
            .flatMap { sealedType ->
                sealedSubclasses(sealedType.unwrap())
                    .filter { it.simpleName == type.name }
                    .map { TypeClassMatcher.ValidMatch(type, withSealedTypeArguments(it, sealedType), SealedSubclassReference(sealedType)) }
            }
            .distinctBy { it.javaType.unwrap() }

    private fun sealedTypesOf(type: ObjectTypeDefinition): List<JavaType> =
        dictionary
            .filterKeys { definition ->
                when (definition) {
                    is UnionTypeDefinition -> definition.withExtensions(typeExtensions).flatMap { it.memberTypes }.filterIsInstance<TypeName>().any { it.name == type.name }
                    is InterfaceTypeDefinition -> type.withExtensions(typeExtensions).flatMap { it.implements }.filterIsInstance<TypeName>().any { it.name == definition.name }
                    else -> false
                }
            }
            .values.mapNotNull { it.javaType }
            .distinct()
            .filter { it.unwrap().kotlin.isSealed }

    // kotlin-reflect also returns the permitted subclasses of Java sealed classes
    private fun sealedSubclasses(clazz: Class<*>): List<Class<*>> =
        clazz.kotlin.sealedSubclasses.flatMap { listOf(it.java) + sealedSubclasses(it.java) }

    private fun withSealedTypeArguments(subclass: Class<*>, sealedType: JavaType): JavaType {
        if (sealedType !is ParameterizedType || subclass.typeParameters.isEmpty()) {
            return subclass
        }

        return TypeUtils.determineTypeArguments(subclass, sealedType)
            ?.takeIf { it.keys.containsAll(subclass.typeParameters.asList()) }
            ?.let { TypeUtils.parameterize(subclass, it) }
            ?: subclass
    }
}
