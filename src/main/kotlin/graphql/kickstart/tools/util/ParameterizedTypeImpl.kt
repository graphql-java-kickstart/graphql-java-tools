package graphql.kickstart.tools.util

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

class ParameterizedTypeImpl(
    private val rawType: Class<*>,
    private val actualTypeArguments: Array<Type>,
    private val ownerType: Type?
) : ParameterizedType {
    override fun getActualTypeArguments(): Array<Type> = actualTypeArguments.clone()

    override fun getRawType(): Type = rawType

    override fun getOwnerType(): Type? = ownerType

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ParameterizedTypeImpl

        if (rawType != other.rawType) return false
        if (ownerType != other.ownerType) return false
        if (!actualTypeArguments.contentEquals(other.actualTypeArguments)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = rawType.hashCode()
        result = 31 * result + (ownerType?.hashCode() ?: 0)
        result = 31 * result + actualTypeArguments.contentHashCode()
        return result
    }

    override fun toString(): String = buildString {
        if (ownerType != null) {
            append(ownerType.typeName)
            append("$")
            append(rawType.simpleName)
        } else {
            append(rawType.name)
        }
        if (actualTypeArguments.isNotEmpty()) {
            actualTypeArguments.joinTo(this, ",", "<", ">", transform = Type::getTypeName)
        }
    }
}
