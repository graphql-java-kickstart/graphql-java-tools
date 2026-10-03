package graphql.kickstart.tools

import com.fasterxml.classmate.ResolvedType
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.ParameterizedTypeImpl
import graphql.kickstart.tools.util.Primitives
import org.apache.commons.lang3.reflect.TypeUtils
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * @author Andrew Potter
 */
internal open class GenericType(protected val mostSpecificType: JavaType, protected val options: SchemaParserOptions) {

    fun isTypeAssignableFromRawClass(type: ParameterizedType, clazz: Class<*>) =
        clazz.isAssignableFrom(getRawClass(type.rawType))

    fun getRawClass() = getRawClass(mostSpecificType)

    fun getRawClass(type: JavaType): Class<*> = TypeUtils.getRawType(type, mostSpecificType)

    fun isAssignableFrom(type: JavaType) = TypeUtils.isAssignable(type, mostSpecificType)

    fun relativeToPotentialParent(declaringType: JavaType): RelativeTo {
        if (declaringType !is Class<*> || declaringType.isInterface) {
            return relativeToType(declaringType)
        }

        val type = getGenericSuperType(mostSpecificType, declaringType)
        if (type == null) {
            error("Unable to find generic type of class ${TypeUtils.toString(declaringType)} relative to ${TypeUtils.toString(mostSpecificType)}")
        } else {
            return relativeToType(type)
        }
    }

    fun relativeToType(declaringType: JavaType) = RelativeTo(declaringType, mostSpecificType, options)

    fun getGenericInterface(targetInterface: Class<*>) = getGenericInterface(mostSpecificType, targetInterface)

    private fun getGenericInterface(type: JavaType?, targetInterface: Class<*>): JavaType? {
        if (type == null) {
            return null
        }

        val raw = type as? Class<*> ?: getRawClass(type)

        if (raw == targetInterface) {
            return type
        }

        val possibleSubInterface = raw.genericInterfaces.find { genericInterface ->
            TypeUtils.isAssignable(genericInterface, targetInterface)
        } ?: raw.interfaces.find { iface ->
            TypeUtils.isAssignable(iface, targetInterface)
        } ?: getGenericInterface(raw.genericSuperclass, targetInterface) ?: return null

        return getGenericInterface(possibleSubInterface, targetInterface)
    }

    fun getGenericSuperType(targetSuperClass: Class<*>) = getGenericSuperType(mostSpecificType, targetSuperClass)

    private fun getGenericSuperType(type: JavaType?, targetSuperClass: Class<*>): JavaType? {
        if (type == null) {
            return null
        }

        val raw = type as? Class<*> ?: TypeUtils.getRawType(type, type)

        if (raw == targetSuperClass) {
            return type
        }

        return getGenericSuperType(raw.genericSuperclass, targetSuperClass)
    }

    class RelativeTo(private val declaringType: JavaType, mostSpecificType: JavaType, options: SchemaParserOptions) : GenericType(mostSpecificType, options) {

        /**
         * Unwrap certain Java types to find the "real" class.
         */
        fun unwrapGenericType(javaType: JavaType): JavaType {
            return when (val type = replaceTypeVariable(javaType)) {
                is ParameterizedType -> {
                    val rawType = type.rawType
                    val genericType = options.genericWrappers.find { it.type == rawType }
                        ?: return type

                    val typeArguments = type.actualTypeArguments
                    if (typeArguments.size <= genericType.index) {
                        throw IndexOutOfBoundsException("Generic type '${TypeUtils.toString(type)}' does not have a type argument at index ${genericType.index}!")
                    }

                    val unwrapsTo = genericType.schemaWrapper.invoke(typeArguments[genericType.index])
                    return unwrapGenericType(unwrapsTo)
                }
                is TypeVariable<*> -> error("Could not resolve type variable '${TypeUtils.toLongString(type)}' of ${TypeUtils.toString(declaringType)} relative to ${TypeUtils.toString(mostSpecificType)}")
                is WildcardType -> type.upperBounds.firstOrNull()
                    ?: error("Unable to unwrap type, wildcard has no upper bound: $type")
                is Class<*> -> if (type.isPrimitive) Primitives.wrap(type) else type
                else -> error("Unable to unwrap type: $type")
            }
        }

        private fun replaceTypeVariable(type: JavaType, resolving: Set<TypeVariable<*>> = emptySet()): JavaType {
            return when (type) {
                is ParameterizedType -> {
                    val actualTypeArguments = type.actualTypeArguments.map { replaceTypeVariable(it, resolving) }.toTypedArray()
                    ParameterizedTypeImpl(type.rawType as Class<*>, actualTypeArguments, type.ownerType?.let { replaceTypeVariable(it, resolving) })
                }
                is WildcardType -> TypeUtils.wildcardType()
                    .withUpperBounds(*type.upperBounds.map { replaceTypeVariable(it, resolving) }.toTypedArray())
                    .withLowerBounds(*type.lowerBounds.map { replaceTypeVariable(it, resolving) }.toTypedArray())
                    .build()
                is ResolvedType -> {
                    if (type.typeParameters.isEmpty()) {
                        type.erasedType
                    } else {
                        val actualTypeArguments = type.typeParameters.map { replaceTypeVariable(it) }.toTypedArray()
                        ParameterizedTypeImpl(type.erasedType, actualTypeArguments, null)
                    }
                }
                is TypeVariable<*> -> {
                    val genericDeclaration = type.genericDeclaration
                    when {
                        // only a variable leaked from a raw type can be bound to a type containing itself (e.g. T -> List<T>),
                        // erase it like the raw type does instead of expanding it forever
                        type in resolving -> TypeUtils.getRawType(type.bounds.first(), null) ?: Any::class.java
                        // the most specific type binds the variables of its supertypes and its owner types those of outer classes,
                        // the declaring type may not
                        genericDeclaration is Class<*> -> generateSequence(mostSpecificType) { (it as? ParameterizedType)?.ownerType }
                            .firstNotNullOfOrNull { TypeUtils.getTypeArguments(it, genericDeclaration)?.get(type) }
                            // keep the full type argument (e.g. List<Foo>) rather than its raw class so nested generics aren't lost
                            ?.let { replaceTypeVariable(it, resolving + type) }
                            ?: type
                        else -> type
                    }
                }
                else -> {
                    type
                }
            }
        }
    }
}
