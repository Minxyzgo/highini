package com.github.minxyzgo.highini.parse

import com.github.minxyzgo.highini.func.*
import com.github.minxyzgo.highini.type.*
import java.io.*
import java.lang.reflect.*

interface HIniReader {
    val parser: Parser

    /**
     * Start this analysis, usually by clearing the cache.
     *
     * Then, use [parseTree] to parse a tree.
     *
     * Finally, use [endParse] to complete the final analysis.
     */
    fun startParse()

    /**
     * The last step of parsing, [startParse] must be called first.
     *
     * This usually does not clear the cache.
     */
    fun endParse()

    /**
     * Use the given [hini] and optional [file] to parse the tree.
     *
     * It should be called between [startParse] and [endParse]
     */
    fun parseTree(
        hini: BufferedReader,
        file: File? = null
    ): ConfigTree

    fun parseSection(
        tree: ConfigTree,
        message: String,
        index: Int,
        sectionString: String
    ): ConfigSection

    /**
     * For final analysis of value, it should be called in [endParse].
     * @param value The value to be parsed.
     * This will change [ConfigValue.value].  Before that, its value and [ConfigValue.stringValue] are the same.
     */
    fun parseValue(
        section: ConfigSection,
        value: ConfigValue
    )

    /**
     * Parse a list.
     *
     * It is not recommended using it directly,
     * because it cannot parse the type of the key value correctly,
     * in this case it should return List<String>.
     *
     * Use [parser] -> [Parser.tags] -> [ConfigTag.reader] for special analysis.
     */
    fun <T> parseList(
        tree: ConfigTree,
        value: String,
        line: Int
    ): List<T>

    /**
     * Parse a map.
     *
     * Non-special analysis will return Map<String, String>.
     *
     * @see parseList
     */
    fun <K, V> parseMap(
        tree: ConfigTree,
        value: String,
        line: Int
    ): Map<K, V>

    fun internalParse(
        type: Class<*>,
        tree: ConfigTree,
        value: String,
        line: Int
    ): Any?

    fun parseStringTemplate(
        tree: ConfigTree,
        value: String,
        line: Int,
        defaultSection: List<ConfigSection>? = null
    ): String

    fun parseReferenceValue(
        tree: ConfigTree,
        stringValue: String,
        line: Int
    ): Any?

    fun parseGetReference(
        key: String,
        configTree: ConfigTree,
        line: Int,
        defaultSection: List<ConfigSection>? = null
    ): TemporaryReference

    fun parseSetReference(
        key: String,
        value: String,
        tree: ConfigTree,
        line: Int
    )

    fun parseBoolean(
        value: String,
        tree: ConfigTree,
        line: Int,
        defaultSection: List<ConfigSection>? = null
    ): Boolp
}

data class TemporarySection(
    val extendsSection: String,
    val line: Int,
    val section: ConfigSection
)


data class TemporaryValue(
    val key: String,
    val value: String,
    val tree: ConfigTree,
    val line: Int,
    val isFinal: Boolean = false
)

/**
 * if parent is [Map] or [List] -> [value], [index] != null, [parent] will be [Map] or [List].
 *
 * if parent is [ConfigSection] -> [value] may be null, [index] != null, [parent] will be the section. Sometimes, [parent] equals [parentSection]
 *
 * if parent is any other object -> [value] may be null, [field] != null, [parent] will be the object.
 *
 * parentSection is always not null, it will be the root section
 */
data class TemporaryReference(
    var value: (() -> Any?)? = null,
    var parent: Any? = null,
    var index: Any? = null,
    var field: Field? = null,
    var parentSection: ConfigSection? = null
)