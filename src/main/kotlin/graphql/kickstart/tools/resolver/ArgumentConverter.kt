package graphql.kickstart.tools.resolver

import tools.jackson.core.JacksonException
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JacksonSerializable
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.deser.DeserializationProblemHandler
import tools.jackson.databind.deser.ValueInstantiator
import tools.jackson.databind.jsontype.TypeSerializer
import tools.jackson.databind.util.TokenBuffer
import java.lang.reflect.Type

/**
 * Converts a coerced argument value to a resolver parameter's type the way ObjectMapper.convertValue does, by writing it into
 * a TokenBuffer and reading it back. The coerced values of many custom scalars don't survive that, e.g. an upload, or a class
 * without a creator. Those values are written as embedded objects instead, which Jackson's deserializers return as they are if
 * they have the type being read, and convert with toString() if a String is read.
 */
internal class ArgumentConverter(private val mapper: ObjectMapper, type: Type) {

    private val javaType = mapper.typeFactory.constructType(type)
    private val isUntyped = javaType.isUntyped()
    private val useBigDecimalForFloats = mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    fun convert(value: Any?): Any? {
        if (value == null) {
            return nullToJavaDefaultReader.readValue(buffer(null))
        }

        if (isUntyped) {
            return read(embed(value) { true } ?: value)
        }

        return try {
            read(value)
        } catch (e: JacksonException) {
            val nonConvertingClasses = HashSet<Class<*>>()
            val embedded = embed(value) { scalar ->
                when {
                    scalar.javaClass in nonConvertingClasses -> true
                    convertsToOwnClass(scalar) -> false
                    else -> {
                        nonConvertingClasses += scalar.javaClass
                        true
                    }
                }
            } ?: throw e
            try {
                read(embedded)
            } catch (retryError: JacksonException) {
                throw retryError.apply { addSuppressed(e) }
            }
        }
    }

    private fun embed(value: Any, shouldEmbed: (Any) -> Boolean): Any? {
        var embedsAny = false
        fun copy(value: Any?): Any? = when (value) {
            null, is String, is Number, is Boolean, is Enum<*> -> value
            is Map<*, *> -> value.mapValuesTo(LinkedHashMap()) { copy(it.value) }
            is Collection<*> -> value.map { copy(it) }
            else -> if (shouldEmbed(value)) EmbeddedValue(value).also { embedsAny = true } else value
        }

        return copy(value).takeIf { embedsAny }
    }

    private fun convertsToOwnClass(value: Any): Boolean = try {
        mapper.readValue(buffer(value), value.javaClass)
        true
    } catch (e: JacksonException) {
        false
    }

    private fun read(value: Any?): Any? = reader.readValue(buffer(value))

    private fun buffer(value: Any?): TokenBuffer =
        writer.writeValueIntoBuffer(value).forceUseOfBigDecimal(useBigDecimalForFloats)

    private val embeddedValueHandler = object : DeserializationProblemHandler() {
        // called for an interface or abstract class, e.g. Part, since no deserializer of it returns embedded values
        override fun handleMissingInstantiator(
            context: DeserializationContext,
            instClass: Class<*>,
            valueInstantiator: ValueInstantiator?,
            p: JsonParser,
            msg: String?
        ): Any? {
            val value = if (p.hasToken(JsonToken.VALUE_EMBEDDED_OBJECT)) p.embeddedObject else null
            return if (instClass.isInstance(value)) value else NOT_HANDLED
        }

        // called for an embedded value read as a bean of another class, which the value is converted to on its own
        override fun handleWeirdNativeValue(context: DeserializationContext, targetType: JavaType, valueToConvert: Any, p: JsonParser): Any? =
            mapper.readValue(buffer(valueToConvert), targetType)
    }

    // FAIL_ON_EMPTY_BEANS whatever the mapper says: a scalar class with no visible properties would otherwise be read back as
    // a new default instance, losing its value without the error that makes it embedded
    private val writer = mapper.writer().without(SerializationFeature.WRAP_ROOT_VALUE).with(SerializationFeature.FAIL_ON_EMPTY_BEANS)

    private val reader by lazy {
        mapper.readerFor(javaType)
            .without(DeserializationFeature.UNWRAP_ROOT_VALUE)
            .withHandler(embeddedValueHandler)
    }

    private val nullToJavaDefaultReader by lazy { reader.without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES) }

    private class EmbeddedValue(private val value: Any) : JacksonSerializable {
        override fun serialize(gen: JsonGenerator, serializers: SerializationContext) {
            gen.writeEmbeddedObject(value)
        }

        override fun serializeWithType(gen: JsonGenerator, serializers: SerializationContext, typeSer: TypeSerializer) {
            gen.writeEmbeddedObject(value)
        }
    }
}

// Object, or a Map, Collection, array or Optional of it, is read by UntypedObjectDeserializer, which returns embedded values as they are
private fun JavaType.isUntyped(): Boolean = isJavaLangObject || (isContainerType || isReferenceType) && contentType.isUntyped()
