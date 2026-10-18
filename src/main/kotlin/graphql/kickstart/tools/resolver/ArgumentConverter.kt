package graphql.kickstart.tools.resolver

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonSerializable
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler
import com.fasterxml.jackson.databind.deser.ValueInstantiator
import com.fasterxml.jackson.databind.jsontype.TypeSerializer
import com.fasterxml.jackson.databind.util.TokenBuffer
import java.io.IOException
import java.lang.reflect.Type

/**
 * ObjectMapper.convertValue serializes a value and reads it back, which the coerced values of many custom scalars don't
 * survive, e.g. an upload, or a LocalDate without the jsr310 module. Those values are written as embedded objects
 * instead, which Jackson's deserializers return as they are if they have the type being read, and convert with
 * toString() if a String is read.
 */
internal class ArgumentConverter(private val mapper: ObjectMapper, type: Type) {

    private val javaType = mapper.typeFactory.constructType(type)
    private val isUntyped = javaType.isUntyped()

    fun convert(value: Any?): Any? {
        if (value == null) {
            return mapper.convertValue(value, javaType)
        }

        if (isUntyped) {
            return read(embed(value) { true } ?: value)
        }

        return try {
            mapper.convertValue<Any?>(value, javaType)
        } catch (e: IllegalArgumentException) {
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
            } catch (retryError: IllegalArgumentException) {
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
        mapper.convertValue(value, value.javaClass)
        true
    } catch (e: IllegalArgumentException) {
        false
    }

    // ObjectMapper.convertValue, but read with the handler for embedded values
    private fun read(value: Any): Any? {
        val buffer = TokenBuffer(mapper, false).forceUseOfBigDecimal(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS))
        return try {
            writer.writeValue(buffer, value)
            reader.readValue<Any?>(buffer.asParser())
        } catch (e: IOException) {
            throw IllegalArgumentException(e.message, e)
        }
    }

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
            mapper.convertValue(valueToConvert, targetType)
    }

    private val writer = mapper.writer().without(SerializationFeature.WRAP_ROOT_VALUE)

    // built on first use, since readerFor looks up the deserializer right away, at schema build, and most arguments
    // never need the reader
    private val reader by lazy {
        mapper.readerFor(javaType)
            .without(DeserializationFeature.UNWRAP_ROOT_VALUE)
            .withHandler(embeddedValueHandler)
    }

    private class EmbeddedValue(private val value: Any) : JsonSerializable {
        override fun serialize(gen: JsonGenerator, serializers: SerializerProvider) = gen.writeEmbeddedObject(value)

        override fun serializeWithType(gen: JsonGenerator, serializers: SerializerProvider, typeSer: TypeSerializer) = gen.writeEmbeddedObject(value)
    }
}

// Object, or a Map, Collection, array or Optional of it, is read by UntypedObjectDeserializer, which returns embedded values as they are
private fun JavaType.isUntyped(): Boolean = isJavaLangObject || (isContainerType || isReferenceType) && contentType.isUntyped()
