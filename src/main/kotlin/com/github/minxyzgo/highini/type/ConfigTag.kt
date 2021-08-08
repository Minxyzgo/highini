package com.github.minxyzgo.highini.type

class ConfigTag(
    val name: String,
    val clazz: Class<*>? = null,
    var isSingle: Boolean = false,
    val reader: ((section: ConfigSection, key: String, value: String, line: Int) -> Any?)? = null
) {
    val children = mutableListOf<ConfigSection>()
    var isNecessary: Boolean = false
        set(value) {
            if(value) isSingle = true
            field = value
        }
}