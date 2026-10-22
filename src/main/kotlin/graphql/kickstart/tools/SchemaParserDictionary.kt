package graphql.kickstart.tools

import kotlin.reflect.KClass

class SchemaParserDictionary {

    private val dictionary = linkedMapOf<String, Class<*>>()

    /**
     * Returns a copy of the entries added so far, in the order they were added. Several names may share a class.
     */
    fun toMap(): Map<String, Class<*>> = dictionary.toMap()

    // TODO remove in 15.0.0 (#900)
    /**
     * Returns a copy of the entries added so far. If several types share a class, the inverse of the copy only maps that class
     * back to the first of them.
     */
    @Deprecated("Use toMap() instead", ReplaceWith("toMap()"))
    @Suppress("DEPRECATION")
    fun getDictionary(): graphql.kickstart.tools.util.BiMap<String, Class<*>> =
        graphql.kickstart.tools.util.BiMap.unmodifiableCopyOf(dictionary)

    /**
     * Add arbitrary classes to the parser's dictionary, overriding the generated type name.
     */
    fun add(name: String, clazz: Class<*>) = this.apply {
        this.dictionary.put(name, clazz)
    }

    /**
     * Add arbitrary classes to the parser's dictionary, overriding the generated type name.
     */
    fun add(name: String, clazz: KClass<*>) = this.apply {
        this.dictionary.put(name, clazz.java)
    }

    /**
     * Add arbitrary classes to the parser's dictionary, overriding the generated type name.
     */
    fun add(dictionary: Map<String, Class<*>>) = this.apply {
        this.dictionary.putAll(dictionary)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun add(clazz: Class<*>) = this.apply {
        this.add(clazz.simpleName, clazz)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun add(clazz: KClass<*>) = this.apply {
        this.add(clazz.java.simpleName, clazz)
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun add(vararg dictionary: Class<*>) = this.apply {
        dictionary.forEach { this.add(it) }
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun add(vararg dictionary: KClass<*>) = this.apply {
        dictionary.forEach { this.add(it) }
    }

    /**
     * Add arbitrary classes to the parser's dictionary.
     */
    fun add(dictionary: Collection<Class<*>>) = this.apply {
        dictionary.forEach { this.add(it) }
    }
}
