package graphql.kickstart.tools

import graphql.kickstart.tools.ScanDictionary.DictionaryReference
import graphql.kickstart.tools.ScanDictionary.FieldTypeReference
import graphql.kickstart.tools.ScanDictionary.InitialDictionaryEntry
import graphql.kickstart.tools.ScanDictionary.UnionMemberReference
import graphql.kickstart.tools.ScanDictionary.UnusedTypeReference
import graphql.kickstart.tools.resolver.FieldResolver
import graphql.kickstart.tools.resolver.FieldResolverError
import graphql.kickstart.tools.resolver.FieldResolverScanner
import graphql.kickstart.tools.resolver.MethodFieldResolver
import graphql.kickstart.tools.resolver.PropertyFieldResolver
import graphql.kickstart.tools.util.*
import graphql.language.*
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.TypeUtil
import org.apache.commons.lang3.ClassUtils
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Modifier

/**
 * @author Andrew Potter
 */
internal class SchemaClassScanner(
    initialDictionary: Map<String, Class<*>>,
    allDefinitions: List<Definition<*>>,
    resolvers: List<GraphQLResolver<*>>,
    private val scalars: CustomScalarMap,
    private val options: SchemaParserOptions
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val rootInfo = RootTypeInfo.fromSchemaDefinitions(allDefinitions.filterIsInstance<SchemaDefinition>())

    private val queryResolvers = resolvers.filterIsInstance<GraphQLQueryResolver>()
    private val mutationResolvers = resolvers.filterIsInstance<GraphQLMutationResolver>()
    private val subscriptionResolvers = resolvers.filterIsInstance<GraphQLSubscriptionResolver>()

    private val resolverInfos = resolvers.asSequence().minus(queryResolvers).minus(mutationResolvers).minus(subscriptionResolvers).map { NormalResolverInfo(it, options) }.toList()

    private val initialDictionary = initialDictionary.mapValues { InitialDictionaryEntry(it.value) }
    private val directiveDefinitions = allDefinitions.filterIsInstance<DirectiveDefinition>()
    private val scalarDefinitions = allDefinitions.filterIsInstance<ScalarTypeDefinition>()

    private val typeDefinitions = allDefinitions.filterIsInstance<TypeDefinition<*>>()
    private val baseTypeNames = typeDefinitions.filter { it !is SDLExtensionDefinition }.map { it.name }.toSet()

    private val loneExtensions = typeDefinitions
        .filter { it is SDLExtensionDefinition && it !is ObjectTypeDefinition && it !is InputObjectTypeDefinition && it.name !in baseTypeNames }
        .distinctBy { it.name }

    private val definitionsByName = typeDefinitions.filter { it !is SDLExtensionDefinition || it in loneExtensions }.associateBy { it.name }
    private val typeExtensions: TypeExtensions = typeDefinitions
        .filter { it is SDLExtensionDefinition && it !in loneExtensions && !extendsOtherKind(it) }
        .groupBy { it.name }
    private val objectDefinitions = allDefinitions.filterIsInstance<ObjectTypeDefinition>().filter { it !is ObjectTypeExtensionDefinition }
    private val objectDefinitionsByName = objectDefinitions.associateBy { it.name }

    private val scalarNames = ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.keys + scalarDefinitions.map { it.name }
    private val fieldResolverScanner = FieldResolverScanner(options, scalarNames)
    private val typeClassMatcher = TypeClassMatcher(definitionsByName)
    private val unvalidatedTypes = mutableSetOf<TypeDefinition<*>>()
    private val queue = linkedSetOf<QueueItem>()
    private val dictionary = ScanDictionary(options, log, typeExtensions, definitionsByName, typeClassMatcher, this.initialDictionary, ::enqueue, ::handleFoundScalarType)
    private val sealedSubclassMatcher = SealedSubclassMatcher(dictionary, typeExtensions)

    private val fieldResolversByType = mutableMapOf<ObjectTypeDefinition, MutableMap<FieldDefinition, FieldResolver>>()
    private val notCallableOnResolver = mutableListOf<NotCallableOnResolver>()

    init {
        initialDictionary.forEach { (name, clazz) ->
            if (!definitionsByName.containsKey(name)) {
                throw SchemaClassScannerError("Class in supplied dictionary '${clazz.name}' specified type name '$name', but a type definition with that name was not found!")
            }
        }

        typeDefinitions.filter { extendsOtherKind(it) }.forEach { extension ->
            val definition = definitionsByName.getValue(extension.name)
            log.warn("Type '${extension.name}' is defined as ${definition.kind()}, but extended as ${extension.kind()}. The extension is ignored.")
        }

        initialDictionary
            .filterKeys { definitionsByName[it] !is InputObjectTypeDefinition }
            .entries.groupBy({ it.value }, { it.key })
            .forEach { (clazz, names) ->
                if (names.size > 1) {
                    throw SchemaClassScannerError("Class in supplied dictionary '${clazz.name}' specified type names ${names.joinToString { "'$it'" }}, but only input object types can share a class!")
                }
            }

        if (options.allowUnimplementedResolvers) {
            log.warn("Option 'allowUnimplementedResolvers' should only be set to true during development, as it can cause schema errors to be moved to query time instead of schema creation time.  Make sure this is turned off in production.")
        }
    }

    /**
     * Attempts to discover GraphQL Type -> Java Class relationships by matching return types/argument types on known fields
     */
    fun scanForClasses(): ScannedSchemaObjects {

        // Figure out what query, mutation and subscription types are called
        val rootTypeHolder = RootTypesHolder(options, rootInfo, definitionsByName, queryResolvers, mutationResolvers, subscriptionResolvers)

        handleRootType(rootTypeHolder.query)
        handleRootType(rootTypeHolder.mutation)
        handleRootType(rootTypeHolder.subscription)

        scanQueue()

        // Loop over all objects scanning each one only once for more objects to discover. Each step only runs once the steps
        // above it have nothing left, and anything new starts over from the top.
        while (true) {
            // Require all implementors of discovered interfaces to be discovered or provided.
            handleDictionaryTypes(getAllObjectTypesImplementingDiscoveredInterfaces()) { "Object type '${it.name}' implements a known interface, but no class could be found for that type name.  Please pass a class for type '${it.name}' in the parser's dictionary." }
            if (scanQueue()) continue

            // Discover unions listed as members of discovered unions, even when no field returns them.
            if (handleNestedUnions()) continue

            // Require all members of discovered unions to be discovered.
            handleDictionaryTypes(getAllObjectTypeMembersOfDiscoveredUnions()) { "Object type '${it.name}' is a member of a known union, but no class could be found for that type name.  Please pass a class for type '${it.name}' in the parser's dictionary." }
            if (scanQueue()) continue

            // Look for unions and interfaces used only by interface fields last, so types that can be reached some other way
            // are still discovered in the same order as before. With includeUnusedTypes the unused types loop below already
            // picks these up one at a time, scanning in between, so leave that path unchanged.
            if (!options.includeUnusedTypes && handleInterfaceFieldTypes()) continue

            break
        }

        handleDirectives()

        // Find unused types and include them if required
        if (options.includeUnusedTypes) {
            // One type at a time, since scanning it can discover other unused types along with their classes
            while (true) {
                val unusedDefinition = (definitionsByName.values - (dictionary.keys.toSet() + unvalidatedTypes))
                    .filter { definition -> definition.name != "PageInfo" }
                    .firstOrNull { canIncludeUnusedType(it) }
                    ?: break

                handleUnusedType(unusedDefinition)
                scanQueue()
            }
        }

        throwIfNotCallableOnResolver()

        return validateAndCreateResult(rootTypeHolder)
    }

    private fun canIncludeUnusedType(definition: TypeDefinition<*>): Boolean = when (definition) {
        is ObjectTypeDefinition, is InterfaceTypeDefinition, is UnionTypeDefinition -> true
        // Enums can't be built without a class, so the ones missing from the dictionary are only reported as unused
        is EnumTypeDefinition -> initialDictionary.containsKey(definition.name)
        else -> false
    }

    private fun handleUnusedType(type: TypeDefinition<*>) {
        val initialEntry = initialDictionary[type.name]
        when {
            initialEntry != null -> dictionary.handleFoundType(type, initialEntry.get(), DictionaryReference())
            // Interfaces and unions are resolved through the classes of their implementations, so they don't need one
            type is InterfaceTypeDefinition || type is UnionTypeDefinition -> dictionary.handleFoundType(type, null, UnusedTypeReference())
            else -> throw SchemaClassScannerError("Object type '${type.name}' is unused and includeUnusedTypes is true. Please pass a class for type '${type.name}' in the parser's dictionary.")
        }
    }

    private fun scanQueue(): Boolean {
        if (queue.isEmpty()) {
            return false
        }

        while (queue.isNotEmpty()) {
            scanQueueItemForPotentialMatches(queue.iterator().run { val item = next(); remove(); item })
        }

        return true
    }

    /**
     * Adds all root resolvers for a type to the list of classes to scan
     */
    private fun handleRootType(rootType: RootType?) {
        if (rootType == null) {
            return
        }

        unvalidatedTypes.add(rootType.type)
        dictionary.scanInterfacesOfType(rootType.type)
        scanResolverInfoForPotentialMatches(rootType.type, rootType.resolverInfo)
    }


    private fun handleDirectives() {
        for (directive in directiveDefinitions) {
            for (input in directive.inputValueDefinitions) {
                handleDirectiveInput(input.type)
            }
        }
    }

    private fun handleDirectiveInput(inputType: Type<*>) {
        val inputTypeName = inputType.unwrap().name
        val typeDefinition = ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS[inputTypeName]
            ?: definitionsByName[inputTypeName]
            ?: error("No ${TypeDefinition::class.java.simpleName} for type name $inputTypeName")
        when (typeDefinition) {
            is ScalarTypeDefinition -> handleFoundScalarType(typeDefinition)
            is EnumTypeDefinition -> handleDirectiveEnum(typeDefinition)
            is InputObjectTypeDefinition -> handleDictionaryTypes(listOf(typeDefinition)) {
                "Input object type '${it.name}' is used in a directive, but no class could be found for that type name. Please pass a class for type '${it.name}' in the parser's dictionary."
            }
        }
    }

    /**
     * Directive argument values never reach resolver methods, so an enum used in a directive doesn't need a class. Without one
     * it goes into [unvalidatedTypes] and, unless a field uses it too, is built with its value names as values, see
     * [validateAndCreateResult].
     */
    private fun handleDirectiveEnum(type: EnumTypeDefinition) {
        if (dictionary.containsKey(type)) {
            return
        }

        val initialEntry = initialDictionary[type.name]
        if (initialEntry != null) {
            dictionary.handleFoundType(type, initialEntry.get(), DictionaryReference())
        } else {
            unvalidatedTypes.add(type)
        }
    }

    /**
     * The names of the types used by the fields, arguments and input fields of [definitions], including fields added by extensions.
     */
    private fun typeNamesUsedByFields(definitions: Set<TypeDefinition<*>>): Set<String> {
        return definitions
            .flatMap { it.withExtensions(typeExtensions) }
            .flatMap { definition ->
                when (definition) {
                    is ImplementingTypeDefinition<*> -> definition.fieldDefinitions.flatMap { field ->
                        field.inputValueDefinitions.map { it.type } + field.type
                    }
                    is InputObjectTypeDefinition -> definition.inputValueDefinitions.map { it.type }
                    else -> emptyList()
                }
            }
            .mapNotNull { it.unwrap().name }
            .toSet()
    }

    private fun validateAndCreateResult(rootTypeHolder: RootTypesHolder): ScannedSchemaObjects {
        dictionary.throwIfEnumWithoutClass()

        initialDictionary
            .filter { !it.value.accessed }
            .forEach {
                log.warn("Dictionary mapping was provided but never used, and can be safely deleted: \"${it.key}\" -> ${it.value.get().name}")
            }

        val observedDefinitions = dictionary.keys.toSet() + unvalidatedTypes

        // The dictionary doesn't need to know what classes are used with scalars.
        // Input types can also be excluded from the dictionary, since it's only used for interfaces, unions, and enums.
        // Union types can also be excluded, as their possible types are resolved recursively later
        val dictionary = TypeClassDictionary(
            dictionary
                .filter {
                    it.value.javaType != null
                        && it.value.typeClass() != Any::class.java
                        && !Map::class.java.isAssignableFrom(it.value.typeClass())
                        && it.key !is InputObjectTypeDefinition
                        && it.key !is UnionTypeDefinition
                }.mapValues { it.value.javaType!! }
        )
        // SchemaParser builds the scalars, this only checks that each one was provided
        val scalars = observedDefinitions.filterIsInstance<ScalarTypeDefinition>()
            .filter {
                // Filter for any defined scalars OR scalars that aren't defined but also aren't standard
                scalars.containsKey(it.name) || !ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.containsKey(it.name)
            }.associate { definition ->
                definition.name to (scalars[definition.name]
                    ?: throw SchemaClassScannerError("Expected a user-defined GraphQL scalar type with name '${definition.name}' but found none!"))
            }

        val unusedDefinitions = (definitionsByName.values - observedDefinitions).toSet()
        unusedDefinitions
            .filter { definition -> definition.name != "PageInfo" }
            .forEach { definition ->
                log.warn("Schema type was defined but can never be accessed, and can be safely deleted: ${definition.name}")
            }

        val fieldResolvers = fieldResolversByType.flatMap { entry -> entry.value.map { it.value } }
        val observedNormalResolverInfos = fieldResolvers.map { it.resolverInfo }.filterIsInstance<NormalResolverInfo>().toSet()
        val observedMultiResolverInfos = fieldResolvers.map { it.resolverInfo }.filterIsInstance<MultiResolverInfo>().flatMap { it.resolverInfoList }.toSet()

        (resolverInfos - observedNormalResolverInfos - observedMultiResolverInfos).forEach { resolverInfo ->
            log.warn("Resolver was provided but no methods on it were used in data fetchers, and can be safely deleted: ${resolverInfo.resolver}")
        }

        validateRootResolversWereUsed(rootTypeHolder.query, fieldResolvers)
        validateRootResolversWereUsed(rootTypeHolder.mutation, fieldResolvers)
        validateRootResolversWereUsed(rootTypeHolder.subscription, fieldResolvers)

        val definitions = observedDefinitions + directiveDefinitions

        // The enums in unvalidatedTypes come from directives without a class, so they can only be built when no field uses them.
        // A field hands the enum's values to user code even when the scanner found no class through it, e.g. an input field bound
        // to a Map or an unimplemented field's argument.
        val typeNamesUsedByFields = typeNamesUsedByFields(observedDefinitions)
        val directiveOnlyEnums = unvalidatedTypes.filterIsInstance<EnumTypeDefinition>().filter { it.name !in typeNamesUsedByFields }.toSet()

        return ScannedSchemaObjects(dictionary, definitions, typeExtensions, scalars, rootInfo, fieldResolversByType.toMap(), unusedDefinitions, directiveOnlyEnums)
    }

    private fun validateRootResolversWereUsed(rootType: RootType?, fieldResolvers: List<FieldResolver>) {
        if (rootType == null) {
            return
        }

        val observedRootTypes = fieldResolvers.asSequence().filter { it.resolverInfo is RootResolverInfo && it.resolverInfo == rootType.resolverInfo }.map { it.search.type }.toSet()
        rootType.resolvers.forEach { resolver ->
            if (rootType.resolverInfo.getRealResolverClass(resolver, options) !in observedRootTypes) {
                log.warn("Root ${rootType.name} resolver was provided but no methods on it were used in data fetchers for GraphQL type '${rootType.type.name}'!  Either remove the ${rootType.resolverInterface.name} interface from the resolver or remove the resolver entirely: $resolver")
            }
        }
    }

    private fun getAllObjectTypesImplementingDiscoveredInterfaces(): List<ObjectTypeDefinition> {
        return dictionary.keys.filterIsInstance<InterfaceTypeDefinition>().map { iface ->
            objectDefinitions.filter { obj ->
                obj.withExtensions(typeExtensions).flatMap { it.implements }.filterIsInstance<TypeName>().any { it.name == iface.name }
            }
        }.flatten().distinctBy { it.name }
    }

    private fun getAllObjectTypeMembersOfDiscoveredUnions(): List<ObjectTypeDefinition> {
        val unionTypeNames = dictionary.keys.filterIsInstance<UnionTypeDefinition>().map { union -> union.name }.toSet()
        return dictionary.keys.filterIsInstance<UnionTypeDefinition>().map { union ->
            union.withExtensions(typeExtensions).flatMap { definition ->
                definition.memberTypes.filterIsInstance<TypeName>().filter { !unionTypeNames.contains(it.name) }.map {
                    objectDefinitionsByName[it.name]
                        ?: throw SchemaClassScannerError("No object type found with name '${it.name}' for union: $definition")
                }
            }
        }.flatten().distinct()
    }

    /**
     * Unions can list other unions as members. Returns whether any new unions were found.
     */
    private fun handleNestedUnions(): Boolean {
        val newUnions = dictionary.keys.filterIsInstance<UnionTypeDefinition>().flatMap { union ->
            union.withExtensions(typeExtensions).flatMap { it.memberTypes }.filterIsInstance<TypeName>()
                .map { definitionsByName[it.name] }
                .filterIsInstance<UnionTypeDefinition>()
                .filter { !dictionary.containsKey(it) }
                .map { it to UnionMemberReference(union) }
        }

        newUnions.forEach { (type, reference) -> dictionary.handleFoundType(type, null, reference) }
        return newUnions.isNotEmpty()
    }

    /**
     * Implementations can narrow an interface field to a member of its union or an implementor of its interface, so such a
     * union or interface may not be reachable from any object field. Returns whether any new types were found.
     */
    private fun handleInterfaceFieldTypes(): Boolean {
        val newTypes = dictionary.keys.filterIsInstance<InterfaceTypeDefinition>().flatMap { iface ->
            iface.withExtensions(typeExtensions).flatMap { it.fieldDefinitions }.mapNotNull { field ->
                val type = definitionsByName[field.type.unwrap().name]
                if ((type is UnionTypeDefinition || type is InterfaceTypeDefinition) && !dictionary.containsKey(type)) {
                    type to FieldTypeReference("${iface.name}.${field.name}")
                } else {
                    null
                }
            }
        }

        newTypes.forEach { (type, reference) -> dictionary.handleFoundType(type, null, reference) }
        return newTypes.isNotEmpty()
    }

    private fun handleDictionaryTypes(types: List<TypeDefinition<*>>, failureMessage: (TypeDefinition<*>) -> String) {
        val typesWithoutClass = mutableListOf<TypeDefinition<*>>()
        types.forEach { type ->
            val dictionaryContainsType = dictionary.filter { it.key.name == type.name }.isNotEmpty()
            if (!unvalidatedTypes.contains(type) && !dictionaryContainsType) {
                val initialEntry = initialDictionary[type.name]
                if (initialEntry != null) {
                    dictionary.handleFoundType(type, initialEntry.get(), DictionaryReference())
                } else {
                    typesWithoutClass.add(type)
                }
            }
        }

        val sealedMatches = typesWithoutClass.filterIsInstance<ObjectTypeDefinition>().mapNotNull { sealedSubclassMatcher.sealedSubclassMatches(it).singleOrNull() }
        if (sealedMatches.isEmpty()) {
            typesWithoutClass.firstOrNull()?.let { throw SchemaClassScannerError(failureMessage(it) + sealedSubclassMatcher.sealedSubclassHint(it)) }
        }

        sealedMatches.forEach { dictionary.handleFoundType(it) }
    }

    /**
     * Scan a new object for types that haven't been mapped yet.
     */
    private fun scanQueueItemForPotentialMatches(item: QueueItem) {
        val resolverInfo = if (item.clazz == Any::class.java) {
            getResolverInfoFromTypeDictionary(item.type.name)
                ?: throw SchemaClassScannerError("The GraphQL schema type '${item.type.name}' maps to a field of type java.lang.Object however there is no matching entry for this type in the type dictionary. You may need to add this type to the dictionary before building the schema.")
        } else {
            getResolverInfoFromDataClass(item.clazz)
        }

        scanResolverInfoForPotentialMatches(item.type, resolverInfo)
    }

    private fun getResolverInfoFromTypeDictionary(typeName: String): ResolverInfo? {
        val dictionaryType = initialDictionary[typeName]?.get()
        return if (dictionaryType != null) {
            getResolverInfoFromDataClass(dictionaryType)
        } else {
            null
        }
    }

    /**
     * Find all resolvers for the data class or any of its supertypes, most specific first.
     */
    private fun getResolverInfoFromDataClass(dataClass: JavaType): ResolverInfo {
        val rawDataClass = dataClass.unwrap()
        val resolverInfoList = resolverInfos
            .filter { it.dataClassType == rawDataClass || isResolverForSupertype(it, rawDataClass) }
            .sortedByDescending { ClassUtils.getAllSuperclasses(it.dataClassType).size + ClassUtils.getAllInterfaces(it.dataClassType).size }

        return when {
            resolverInfoList.isEmpty() -> DataClassResolverInfo(dataClass)
            resolverInfoList.size == 1 && resolverInfoList.single().dataClassType == dataClass -> resolverInfoList.single()
            else -> MultiResolverInfo(resolverInfoList, dataClass)
        }
    }

    private fun isResolverForSupertype(resolverInfo: NormalResolverInfo, dataClass: Class<*>) =
        resolverInfo.dataClassType != Any::class.java && resolverInfo.dataClassType.isAssignableFrom(dataClass)

    private fun scanResolverInfoForPotentialMatches(type: ObjectTypeDefinition, resolverInfo: ResolverInfo) {
        type.withExtensions(typeExtensions).flatMap { it.fieldDefinitions }.forEach { field ->
            val fieldResolver = fieldResolverScanner.findFieldResolver(field, resolverInfo)
            collectIfNotCallableOnResolver(type, fieldResolver)

            fieldResolversByType.getOrPut(type) { mutableMapOf() }[fieldResolver.field] = fieldResolver

            fieldResolver.scanForMatches().forEach { potentialMatch ->
                dictionary.handleFoundType(typeClassMatcher.match(potentialMatch))
            }
            warnIfParameterMissing(type, fieldResolver)
        }
    }

    private fun collectIfNotCallableOnResolver(type: ObjectTypeDefinition, fieldResolver: FieldResolver) {
        val source = fieldResolver.search.source ?: return
        val member: Member = when (fieldResolver) {
            is MethodFieldResolver -> fieldResolver.method
            is PropertyFieldResolver -> fieldResolver.property
            else -> return
        }
        if (Modifier.isStatic(member.modifiers) || member.declaringClass.isInstance(source)) {
            return
        }

        notCallableOnResolver += NotCallableOnResolver(
            "Field '${type.name}.${fieldResolver.field.name}' resolves to ${member.declaringClass.name}.${member.name}, but the resolver is a " +
                "${source.javaClass.name}, which isn't a ${member.declaringClass.name}, so every query of the field will fail.",
            member is Field
        )
    }

    private fun throwIfNotCallableOnResolver() {
        if (notCallableOnResolver.isEmpty()) {
            return
        }

        // class-based proxies don't delegate field reads to the target, so a field needs a getter too
        val fix = "Use class-based proxies instead, e.g. proxyTargetClass = true in Spring." +
            if (notCallableOnResolver.any { it.isField }) " For a public field, also add a getter and read it through that." else ""
        throw FieldResolverError(
            notCallableOnResolver.joinToString("\n", postfix = "\nThis happens with JDK dynamic proxies, which only implement interfaces. $fix") { it.message }
        )
    }

    // TODO throw a FieldResolverError instead in 15.0.0 (#889)
    private fun warnIfParameterMissing(type: ObjectTypeDefinition, fieldResolver: FieldResolver) {
        if (fieldResolver !is MethodFieldResolver) {
            return
        }

        fieldResolver.field.inputValueDefinitions.forEachIndexed { index, argument ->
            val parameter = fieldResolver.getMethodParameterType(index)?.let(fieldResolver.typeResolver::getRawClass) ?: return@forEachIndexed
            if (!fieldResolverScanner.isContextParameter(parameter, argument)) {
                return@forEachIndexed
            }

            val method = fieldResolver.method
            log.warn(
                "Field '${type.name}.${fieldResolver.field.name}' has no parameter for its argument '${argument.name}: " +
                    "${TypeUtil.simplePrint(argument.type)}' in ${method.declaringClass.name}.${method.name}, so the ${parameter.simpleName} " +
                    "parameter gets the argument instead. Add a parameter for each argument, in order, before the ${parameter.simpleName} parameter."
            )
        }
    }

    private fun handleFoundScalarType(type: ScalarTypeDefinition) {
        unvalidatedTypes.add(type)
    }

    private fun extendsOtherKind(definition: TypeDefinition<*>): Boolean {
        val type = definitionsByName[definition.name]
        return definition is SDLExtensionDefinition && type != null && type.kind() != definition.kind()
    }

    private fun TypeDefinition<*>.kind(): String = when (this) {
        is ObjectTypeDefinition -> "an object type"
        is InterfaceTypeDefinition -> "an interface"
        is UnionTypeDefinition -> "a union"
        is EnumTypeDefinition -> "an enum"
        is InputObjectTypeDefinition -> "an input object type"
        is ScalarTypeDefinition -> "a scalar"
        else -> javaClass.simpleName
    }

    private fun enqueue(graphQLType: ObjectTypeDefinition, javaType: JavaType) {
        queue.add(QueueItem(graphQLType, javaType))
    }

    private data class QueueItem(val type: ObjectTypeDefinition, val clazz: JavaType)

    private data class NotCallableOnResolver(val message: String, val isField: Boolean)

    private class RootTypesHolder(
        options: SchemaParserOptions,
        rootInfo: RootTypeInfo,
        definitionsByName: Map<String, TypeDefinition<*>>,
        queryResolvers: List<GraphQLQueryResolver>,
        mutationResolvers: List<GraphQLMutationResolver>,
        subscriptionResolvers: List<GraphQLSubscriptionResolver>
    ) {
        private val queryName = rootInfo.getQueryName()
        private val mutationName = rootInfo.getMutationName()
        private val subscriptionName = rootInfo.getSubscriptionName()

        private val queryDefinition = definitionsByName[queryName]
        private val mutationDefinition = definitionsByName[mutationName]
        private val subscriptionDefinition = definitionsByName[subscriptionName]

        private val queryResolverInfo = RootResolverInfo(queryResolvers, options)
        private val mutationResolverInfo = RootResolverInfo(mutationResolvers, options)
        private val subscriptionResolverInfo = RootResolverInfo(subscriptionResolvers, options, isSubscription = true)

        val query = createRootType("query", queryDefinition, queryName, true, queryResolvers, GraphQLQueryResolver::class.java, queryResolverInfo)
        val mutation = createRootType("mutation", mutationDefinition, mutationName, rootInfo.isMutationRequired(), mutationResolvers, GraphQLMutationResolver::class.java, mutationResolverInfo)
        val subscription = createRootType("subscription", subscriptionDefinition, subscriptionName, rootInfo.isSubscriptionRequired(), subscriptionResolvers, GraphQLSubscriptionResolver::class.java, subscriptionResolverInfo)

        private fun createRootType(name: String, type: TypeDefinition<*>?, typeName: String, required: Boolean, resolvers: List<GraphQLRootResolver>, resolverInterface: Class<*>, resolverInfo: RootResolverInfo): RootType? {
            if (type == null) {
                if (required) {
                    throw SchemaClassScannerError("Type definition for root $name type '$typeName' not found!")
                }

                return null
            }

            if (type !is ObjectTypeDefinition) {
                throw SchemaClassScannerError("Expected root query type's type to be ${ObjectTypeDefinition::class.java.simpleName}, but it was ${type.javaClass.simpleName}")
            }

            // Find query resolver class
            if (resolvers.isEmpty()) {
                throw SchemaClassScannerError("No Root resolvers for $name type '$typeName' found!  Provide one or more ${resolverInterface.name} to the builder.")
            }

            return RootType(name, type, resolvers, resolverInterface, resolverInfo)
        }
    }

    internal class RootType(val name: String, val type: ObjectTypeDefinition, val resolvers: List<GraphQLRootResolver>, val resolverInterface: Class<*>, val resolverInfo: RootResolverInfo)
}

internal class SchemaClassScannerError(message: String, throwable: Throwable? = null) : RuntimeException(message, throwable)
