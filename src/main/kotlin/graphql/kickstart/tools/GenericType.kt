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
internal class GenericType(private val containingType: JavaType, private val options: SchemaParserOptions) {

    fun getRawClass() = getRawClass(containingType)

    fun getRawClass(type: JavaType): Class<*> = TypeUtils.getRawType(type, containingType)

    fun isAssignableFrom(type: JavaType) = TypeUtils.isAssignable(type, containingType)

    /**
     * Turns a type as declared in the source (a method's return or parameter type, or a field's type) into the type
     * the GraphQL type is matched against:
     * - type variables are resolved relative to the containing type,
     *   e.g. `T` of `AbstractItem<T>.getId()` becomes `Long` for `class Item : AbstractItem<Long>()`
     * - generic wrappers are replaced by their wrapped type, e.g. `CompletableFuture<Foo>` becomes `Foo`
     * - wildcards are replaced by their upper bound, e.g. `? extends Foo` becomes `Foo`
     * - primitives are replaced by their boxed class, e.g. `int` becomes `Integer`
     */
    fun unwrapGenericType(javaType: JavaType): JavaType {
        return when (val type = resolveTypeVariables(javaType)) {
            is ParameterizedType -> {
                val rawType = type.rawType
                val wrapper = options.genericWrappers.find { it.type == rawType }
                    ?: return type

                val typeArguments = type.actualTypeArguments
                if (typeArguments.size <= wrapper.index) {
                    throw IndexOutOfBoundsException("Generic type '${TypeUtils.toString(type)}' does not have a type argument at index ${wrapper.index}!")
                }

                val unwrapsTo = wrapper.schemaWrapper.invoke(typeArguments[wrapper.index])
                unwrapGenericType(unwrapsTo)
            }
            is TypeVariable<*> -> error("Could not resolve type variable '${TypeUtils.toLongString(type)}' relative to ${TypeUtils.toString(containingType)}")
            is WildcardType -> type.upperBounds.firstOrNull()
                ?: error("Unable to unwrap type, wildcard has no upper bound: $type")
            is Class<*> -> if (type.isPrimitive) Primitives.wrap(type) else type
            else -> error("Unable to unwrap type: $type")
        }
    }

    private fun resolveTypeVariables(type: JavaType, resolving: Set<TypeVariable<*>> = emptySet()): JavaType {
        return when (type) {
            is ParameterizedType -> {
                val actualTypeArguments = type.actualTypeArguments.map { resolveTypeVariables(it, resolving) }.toTypedArray()
                ParameterizedTypeImpl(type.rawType as Class<*>, actualTypeArguments, type.ownerType?.let { resolveTypeVariables(it, resolving) })
            }
            is WildcardType -> TypeUtils.wildcardType()
                .withUpperBounds(*type.upperBounds.map { resolveTypeVariables(it, resolving) }.toTypedArray())
                .withLowerBounds(*type.lowerBounds.map { resolveTypeVariables(it, resolving) }.toTypedArray())
                .build()
            is ResolvedType -> {
                if (type.typeParameters.isEmpty()) {
                    type.erasedType
                } else {
                    val actualTypeArguments = type.typeParameters.map { resolveTypeVariables(it) }.toTypedArray()
                    ParameterizedTypeImpl(type.erasedType, actualTypeArguments, null)
                }
            }
            is TypeVariable<*> -> {
                val genericDeclaration = type.genericDeclaration
                when {
                    // only a variable leaked from a raw type can be bound to a type containing itself (e.g. T -> List<T>),
                    // erase it like the raw type does instead of expanding it forever
                    type in resolving -> TypeUtils.getRawType(type.bounds.first(), null) ?: Any::class.java
                    // the containing type binds the variables of all its supertypes
                    genericDeclaration is Class<*> -> generateSequence(containingType) { (it as? ParameterizedType)?.ownerType }
                        // an inner class can also use the variables of its outer class, those are bound by its owner type (e.g. Connection<Owner>.Entry)
                        .firstNotNullOfOrNull { TypeUtils.getTypeArguments(it, genericDeclaration)?.get(type) }
                        // keep the full type argument (e.g. List<Foo>) rather than its raw class so nested generics aren't lost
                        ?.let { resolveTypeVariables(it, resolving + type) }
                        ?: type
                    else -> type
                }
            }
            else -> type
        }
    }
}
