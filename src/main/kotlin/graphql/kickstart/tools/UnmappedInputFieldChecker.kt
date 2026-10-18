package graphql.kickstart.tools

import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext
import com.fasterxml.jackson.databind.deser.std.ContainerDeserializerBase
import com.fasterxml.jackson.databind.deser.std.ReferenceTypeDeserializer
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import graphql.kickstart.tools.resolver.MethodFieldResolver
import graphql.kickstart.tools.util.unwrap
import graphql.language.TypeName
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import org.slf4j.LoggerFactory

/**
 * Checks that the classes of resolver arguments have a property for each field of their input objects, see [UnmappedInputFields].
 *
 * The fields are looked up in the deserializer that the resolver's ObjectMapper converts the argument with, not in the class
 * itself, so that a field counts as mapped whenever Jackson maps it. An input type can be used by fields whose mappers are
 * configured differently, so it's checked once for each mapper.
 */
internal class UnmappedInputFieldChecker(
    private val inputObjects: Map<String, GraphQLInputObjectType>,
    private val unmappedInputFields: UnmappedInputFields
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // a deserializer is cached by its mapper, so this is per mapper, class and input type, and ends recursive input types
    private val checked = mutableSetOf<Pair<JsonDeserializer<*>, String>>()

    // reported once for each consequence, with the first argument found using it, since mappers differ in ignoring unknown properties
    private val messages = linkedMapOf<Unmapped, String>()

    fun check(objectType: String, resolver: MethodFieldResolver) {
        resolver.field.inputValueDefinitions.forEachIndexed { index, argument ->
            val inputObject = (argument.type.unwrap() as? TypeName)?.let { inputObjects[it.name] } ?: return@forEachIndexed
            val usage = "argument '${argument.name}' of field '$objectType.${resolver.field.name}'"

            try {
                val parameterType = resolver.getMethodParameterType(index) ?: return@forEachIndexed
                val mapper = resolver.objectMapper
                val context = (mapper.deserializationContext as? DefaultDeserializationContext)
                    ?.createDummyInstance(mapper.deserializationConfig) ?: return@forEachIndexed
                // the same lookup as convertValue's when it converts the argument
                val deserializer = context.findRootValueDeserializer(mapper.typeFactory.constructType(parameterType))
                checkFields(mapper, context, deserializer, null, inputObject, usage)
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                // e.g. a class Jackson can't deserialize, or one that refers to a missing class, which only fails once the argument is
                // converted, as it did before this check. Errors are caught too, since the lookup used to run on the first query
                log.debug("Can't check the input fields of $usage with Jackson", e)
            }
        }
    }

    fun report() {
        if (messages.isEmpty()) {
            return
        }

        if (unmappedInputFields == UnmappedInputFields.FAIL) {
            throw SchemaError(messages.values.joinToString("\n"))
        }
        messages.values.forEach { log.warn(it) }
    }

    private fun checkFields(
        mapper: ObjectMapper,
        context: DefaultDeserializationContext,
        deserializer: JsonDeserializer<*>?,
        property: BeanProperty?,
        inputObject: GraphQLInputObjectType,
        usage: String
    ) {
        val beanDeserializer = beanDeserializer(context, deserializer, property) ?: return
        if (!checked.add(beanDeserializer to inputObject.name)) {
            return
        }

        inputObject.fields.forEach { field ->
            val property = beanDeserializer.findProperty(field.name)
            if (property == null) {
                knownPropertiesIfUnmapped(mapper, context, beanDeserializer, field.name)?.let { knownProperties ->
                    val unmapped = Unmapped(inputObject.name, field.name, beanDeserializer.handledType(), consequence(mapper, inputObject, field))
                    messages.getOrPut(unmapped) { message(unmapped, usage, knownProperties) }
                }
            } else if (property.valueTypeDeserializer == null) {
                // a polymorphic property's class is only known once its type id is read
                inputObjectOf(field.type)?.let { checkFields(mapper, context, property.valueDeserializer, property, it, usage) }
            }
        }
    }

    // the deserializer of the class an input object is converted to, past the lists, arrays and Optionals around it
    private tailrec fun beanDeserializer(context: DeserializationContext, deserializer: JsonDeserializer<*>?, property: BeanProperty?): BeanDeserializerBase? =
        when (deserializer) {
            // a delegating creator gets the whole input object, e.g. as a Map
            is BeanDeserializerBase -> deserializer.takeUnless { it.valueInstantiator.canCreateUsingDelegate() }
            // a Map gets the fields as its keys
            is ContainerDeserializerBase<*> ->
                if (deserializer.valueType.isMapLikeType || isPolymorphic(context, deserializer.contentType)) null
                else beanDeserializer(context, deserializer.contentDeserializer, property)
            is ReferenceTypeDeserializer<*> -> {
                val referencedType = deserializer.valueType.referencedType
                // looked up with the property, like Jackson does, so that its annotations apply, e.g. @JsonIgnoreProperties
                if (isPolymorphic(context, referencedType)) null
                else beanDeserializer(context, context.findContextualValueDeserializer(referencedType, property), property)
            }
            else -> null
        }

    /**
     * Whether the elements of a list, an array or an Optional are read with type info, so that their class is only known once their
     * type id is read. Their deserializer is the base class's, which doesn't know the subclasses' properties. The same lookup as
     * Jackson's when it creates the deserializer: type info from a property's annotation, or else from the class or default typing.
     */
    private fun isPolymorphic(context: DeserializationContext, contentType: JavaType): Boolean =
        contentType.getTypeHandler<Any?>() != null || context.factory.findTypeDeserializer(context.config, contentType) != null

    /**
     * Returns the properties Jackson knows if it fails on the field, or null if it maps or ignores it.
     *
     * `{"<field>": null}` is read into a placeholder object rather than an instance of the class, so neither its constructors nor
     * its setters are called, but the field still goes through Jackson's handling of a property the class doesn't have: ignored
     * names, any setters, ignoreUnknown and problem handlers. Unknown properties are made to fail even if the mapper ignores them.
     * Any other exception, e.g. from a deserializer that can't read into an existing object, means the field can't be checked.
     */
    private fun knownPropertiesIfUnmapped(
        mapper: ObjectMapper,
        context: DefaultDeserializationContext,
        deserializer: BeanDeserializerBase,
        field: String
    ): Collection<Any>? {
        val config = mapper.deserializationConfig.with(FAIL_ON_UNKNOWN_PROPERTIES)
        return try {
            mapper.treeAsTokens(mapper.createObjectNode().putNull(field)).use { parser ->
                parser.nextToken()
                deserializer.deserialize(parser, context.createInstance(config, parser, mapper.injectableValues), Any())
            }
            null
        } catch (e: UnrecognizedPropertyException) {
            if (e.propertyName == field) e.knownPropertyIds.orEmpty() else null
        } catch (e: Exception) {
            null
        }
    }

    private fun inputObjectOf(type: GraphQLType): GraphQLInputObjectType? =
        (GraphQLTypeUtil.unwrapAllAs<GraphQLType>(type) as? GraphQLNamedType)?.let { inputObjects[it.name] }

    private fun consequence(mapper: ObjectMapper, inputObject: GraphQLInputObjectType, field: GraphQLInputObjectField): String =
        when {
            !mapper.deserializationConfig.isEnabled(FAIL_ON_UNKNOWN_PROPERTIES) -> "drops the field's value, since the ObjectMapper ignores unknown properties"
            field.hasSetDefaultValue() -> "fails for every '${inputObject.name}' value sent, since the field has a default value"
            else -> "fails whenever the field is sent"
        }

    private fun message(unmapped: Unmapped, usage: String, knownProperties: Collection<Any>): String {
        val properties = knownProperties.map { it.toString() }.sorted().ifEmpty { listOf("none") }

        return "Input field '${unmapped.inputObject}.${unmapped.field}' has no matching property in ${unmapped.clazz.name}. " +
            "Converting $usage ${unmapped.consequence}. " +
            "Known properties: ${properties.joinToString(", ")}. " +
            "Rename the property or map it with @JsonProperty(\"${unmapped.field}\"). If it's left unmapped on purpose, list it in @JsonIgnoreProperties on the class."
    }

    private data class Unmapped(val inputObject: String, val field: String, val clazz: Class<*>, val consequence: String)
}
