package com.github.minxyzgo.highini.type

class ConfigSection(
    override val name: String,
    override val stringValue: String,
    override val line: Int,
    var extendsSection: ConfigSection? = null,
    val parent: ConfigTree? = null,
    var isFinal: Boolean = false,
    val tag: ConfigTag? = null,
) : Config {
    internal var isExtended: Boolean = false
    val mutableMap = mutableMapOf<String, ConfigValue>()
    val children = mutableListOf<ConfigSection>()


    operator fun get(key: String) = mutableMap[key]
    operator fun set(key: String, value: ConfigValue) {
        mutableMap[key] = value
    }
}