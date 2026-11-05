package graphql.kickstart.tools

import graphql.kickstart.tools.ScanDictionary.SealedSubclassReference
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.unwrap
import graphql.language.TypeName
import org.apache.commons.lang3.reflect.TypeUtils
import java.lang.reflect.ParameterizedType

internal class SealedSubclassMatcher(
    private val dictionary: Map<MergedType<*>, ScanDictionary.EntryView>,
) {
    fun sealedSubclassHint(type: MergedType<*>): String {
        if (type !is MergedType.Object) {
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

    fun sealedSubclassMatches(type: MergedType.Object): List<TypeClassMatcher.ValidMatch> =
        sealedTypesOf(type)
            .flatMap { sealedType ->
                sealedSubclasses(sealedType.unwrap())
                    .filter { it.simpleName == type.name }
                    .map { TypeClassMatcher.ValidMatch(type, withSealedTypeArguments(it, sealedType), SealedSubclassReference(sealedType)) }
            }
            .distinctBy { it.javaType.unwrap() }

    private fun sealedTypesOf(type: MergedType.Object): List<JavaType> =
        dictionary
            .filterKeys { definition ->
                when (definition) {
                    is MergedType.Union -> definition.members.filterIsInstance<TypeName>().any { it.name == type.name }
                    is MergedType.Interface -> type.interfaces.filterIsInstance<TypeName>().any { it.name == definition.name }
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
