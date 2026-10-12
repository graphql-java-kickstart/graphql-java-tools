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
import java.util.concurrent.ConcurrentHashMap

/**
 * Converts an argument value that graphql-java already coerced to the type of the resolver method's parameter.
 *
 * Jackson converts a value by serializing it and deserializing it again, which the coerced values of custom scalars
 * often don't survive, e.g. an upload, or a LocalDate without the jsr310 module. Those are handed to Jackson as embedded
 * objects instead, which its deserializers return as they are if they have the type being read. Where a String is read,
 * they're converted with toString().
 *
 * An untyped parameter, e.g. a Map<String, Any?>, gets the coerced values as they are, apart from strings, numbers,
 * booleans and enums, which are converted as before.
 */
internal class ArgumentConverter(private val mapper: ObjectMapper, type: Type) {

    private val javaType = mapper.typeFactory.constructType(type)
    private val isUntyped = javaType.isUntyped()
    private val nonConvertingClasses: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

    fun convert(value: Any?): Any? {
        if (value == null) {
            return mapper.convertValue(value, javaType)
        }

        if (isUntyped) {
            // nothing that reads an untyped value depends on its serialized form, so the coerced values are kept
            val embedded = embedValues(value) { true }
            return if (embedded != null) readEmbedded(embedded) else mapper.convertValue(value, javaType)
        }

        // a deserializer may depend on the serialized form, so values are only embedded if converting fails
        return try {
            mapper.convertValue<Any?>(value, javaType)
        } catch (e: IllegalArgumentException) {
            val embedded = embedValues(value) { !convertsToOwnClass(it) } ?: throw e
            try {
                readEmbedded(embedded)
            } catch (embeddedError: IllegalArgumentException) {
                // the values Jackson couldn't convert likely hid the actual problem
                throw embeddedError.apply { addSuppressed(e) }
            }
        }
    }

    /**
     * Returns a copy of the value with the scalar values [isEmbedded] matches wrapped in [EmbeddedValue], or null if there are none.
     */
    private fun embedValues(value: Any, isEmbedded: (Any) -> Boolean): Any? {
        var embedsAny = false
        fun embed(value: Any?): Any? = when (value) {
            null, is String, is Number, is Boolean, is Enum<*> -> value
            is Map<*, *> -> value.mapValuesTo(LinkedHashMap()) { embed(it.value) }
            is Collection<*> -> value.map { embed(it) }
            else -> if (isEmbedded(value)) EmbeddedValue(value).also { embedsAny = true } else value
        }

        return embed(value).takeIf { embedsAny }
    }

    // failing to convert can be expensive, e.g. an upload, so those classes are remembered. Whether a value converts
    // can depend on what it holds, so values of the other classes are checked every time
    private fun convertsToOwnClass(value: Any): Boolean {
        if (value.javaClass in nonConvertingClasses) {
            return false
        }
        return try {
            mapper.convertValue(value, value.javaClass)
            true
        } catch (e: IllegalArgumentException) {
            nonConvertingClasses.add(value.javaClass)
            false
        }
    }

    // like ObjectMapper.convertValue, but reads embedded values
    private fun readEmbedded(value: Any): Any? {
        val buffer = TokenBuffer(mapper, false).forceUseOfBigDecimal(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS))
        return try {
            mapper.writer().without(SerializationFeature.WRAP_ROOT_VALUE).writeValue(buffer, value)
            mapper.readerFor(javaType)
                .without(DeserializationFeature.UNWRAP_ROOT_VALUE)
                .withHandler(embeddedValueHandler)
                .readValue<Any?>(buffer.asParser())
        } catch (e: IOException) {
            throw IllegalArgumentException(e.message, e)
        }
    }

    private val embeddedValueHandler = object : DeserializationProblemHandler() {
        // an interface or abstract class, e.g. Part, has no deserializer that returns embedded values
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

        // a bean of another class, which the value is converted to on its own
        override fun handleWeirdNativeValue(context: DeserializationContext, targetType: JavaType, valueToConvert: Any, p: JsonParser): Any? =
            mapper.convertValue(valueToConvert, targetType)
    }

    private class EmbeddedValue(private val value: Any) : JsonSerializable {
        override fun serialize(gen: JsonGenerator, serializers: SerializerProvider) = gen.writeEmbeddedObject(value)

        override fun serializeWithType(gen: JsonGenerator, serializers: SerializerProvider, typeSer: TypeSerializer) = gen.writeEmbeddedObject(value)
    }
}

// Object, or a Map, Collection, array or Optional of it, is read by UntypedObjectDeserializer, which returns embedded values as they are
private fun JavaType.isUntyped(): Boolean = isJavaLangObject || (isContainerType || isReferenceType) && contentType.isUntyped()
