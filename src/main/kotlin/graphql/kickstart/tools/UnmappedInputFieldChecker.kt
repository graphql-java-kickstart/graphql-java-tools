package graphql.kickstart.tools

import graphql.kickstart.tools.resolver.MethodFieldResolver
import graphql.kickstart.tools.util.unwrap
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import org.slf4j.LoggerFactory
import tools.jackson.core.JsonParser
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.PropertyName
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.deser.bean.BeanDeserializerBase
import tools.jackson.databind.deser.std.ContainerDeserializerBase
import tools.jackson.databind.deser.std.ReferenceTypeDeserializer
import tools.jackson.databind.exc.UnrecognizedPropertyException
import tools.jackson.databind.node.NullNode

internal class UnmappedInputFieldChecker(
    private val inputObjects: Map<String, GraphQLInputObjectType>,
    private val unmappedInputFields: UnmappedInputFields
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val checked = mutableSetOf<Pair<ValueDeserializer<*>, String>>()

    private val messages = linkedMapOf<Unmapped, String>()

    fun check(objectType: String, resolver: MethodFieldResolver) {
        resolver.field.inputValueDefinitions.forEachIndexed { index, argument ->
            val inputObject = inputObjects[argument.type.unwrap().name] ?: return@forEachIndexed
            val usage = "argument '${argument.name}' of field '$objectType.${resolver.field.name}'"

            try {
                val parameterType = resolver.getMethodParameterType(index) ?: return@forEachIndexed
                val reads = StrictReads(resolver.objectMapper)
                val deserializer = reads.context.findRootValueDeserializer(reads.mapper.typeFactory.constructType(parameterType))
                checkFields(reads, deserializer, null, inputObject, usage)
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
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
        reads: StrictReads,
        deserializer: ValueDeserializer<*>?,
        property: BeanProperty?,
        inputObject: GraphQLInputObjectType,
        usage: String
    ) {
        val beanDeserializer = beanDeserializer(reads.context, deserializer, property) ?: return
        if (!checked.add(beanDeserializer to inputObject.name)) {
            return
        }

        inputObject.fields.forEach { field ->
            val property = beanDeserializer.findProperty(PropertyName.construct(field.name))
            if (property == null) {
                reads.knownPropertiesIfUnmapped(beanDeserializer, field.name)?.let { knownProperties ->
                    val unmapped = Unmapped(inputObject.name, field.name, beanDeserializer.handledType(), consequence(reads.mapper, inputObject, field))
                    messages.getOrPut(unmapped) { message(unmapped, usage, knownProperties) }
                }
            } else if (property.valueTypeDeserializer == null) {
                // a polymorphic property's class is only known once its type id is read
                inputObjectOf(field.type)?.let { checkFields(reads, property.valueDeserializer, property, it, usage) }
            }
        }
    }

    private tailrec fun beanDeserializer(context: DeserializationContext, deserializer: ValueDeserializer<*>?, property: BeanProperty?): BeanDeserializerBase? =
        when (deserializer) {
            is BeanDeserializerBase -> deserializer.takeUnless { it.valueInstantiator.canCreateUsingDelegate() }
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
     * The deserializer of elements read with type info is the base class's, which doesn't know the subclasses' properties. The
     * same lookup as Jackson's when it creates the deserializer: type info from a property's annotation, or else from the class
     * or default typing.
     */
    private fun isPolymorphic(context: DeserializationContext, contentType: JavaType): Boolean =
        contentType.typeHandler != null || context.findTypeDeserializer(contentType) != null

    private fun inputObjectOf(type: GraphQLType): GraphQLInputObjectType? =
        inputObjects[GraphQLTypeUtil.unwrapAllAs<GraphQLNamedType>(type).name]

    private fun consequence(mapper: ObjectMapper, inputObject: GraphQLInputObjectType, field: GraphQLInputObjectField): String =
        when {
            !mapper.deserializationConfig().isEnabled(FAIL_ON_UNKNOWN_PROPERTIES) -> "drops the field's value, since the ObjectMapper ignores unknown properties"
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

    /**
     * A parser made by an ObjectReader carries the reader's context, which keeps the mapper's injectable values and problem
     * handlers and shares its deserializer cache.
     */
    private class StrictReads(val mapper: ObjectMapper) {

        private val reader = mapper.reader().with(FAIL_ON_UNKNOWN_PROPERTIES)

        val context: DeserializationContext = contextOf(reader.treeAsTokens(NullNode.instance))

        /**
         * `{"<field>": null}` is read into a placeholder object rather than an instance of the class, so neither its constructors
         * nor its setters are called, but the field still goes through Jackson's handling of a property the class doesn't have:
         * ignored names, any setters, ignoreUnknown and problem handlers.
         */
        fun knownPropertiesIfUnmapped(deserializer: BeanDeserializerBase, field: String): Collection<Any>? = try {
            reader.treeAsTokens(mapper.createObjectNode().putNull(field)).use { parser ->
                parser.nextToken()
                deserializer.deserialize(parser, contextOf(parser), Any())
            }
            null
        } catch (e: UnrecognizedPropertyException) {
            if (e.propertyName == field) e.knownPropertyIds.orEmpty() else null
        } catch (e: Exception) {
            null
        }

        private fun contextOf(parser: JsonParser) = parser.objectReadContext() as DeserializationContext
    }
}
