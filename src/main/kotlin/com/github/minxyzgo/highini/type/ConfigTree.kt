package com.github.minxyzgo.highini.type

import java.io.*

class ConfigTree(
    //TODO base 64
    val string: String,
    var name: String? = null,
    val file: File? = null
) {
    val mutableMap = mutableMapOf<String, ConfigSection>()

    operator fun get(index: String) = mutableMap[index]
    operator fun set(index: String, value: ConfigSection) {
        mutableMap[index] = value
    }
}