package com.github.minxyzgo.highini.type

@Suppress("unused", "UNCHECKED_CAST")
class ConfigValue(
    override val name: String,
    var value: Any?,
    override val stringValue: String,
    override val line: Int,
    val parent: ConfigSection,
    val isFinal: Boolean = false
): Config  {
    fun <T> get() = value as T

    fun <T> getOrDefault(default: T): T {
        if(value != null) return value as T
        return default
    }

    override fun toString(): String = "[value=$value]"
}