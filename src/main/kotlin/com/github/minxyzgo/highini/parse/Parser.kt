package com.github.minxyzgo.highini.parse

import com.github.minxyzgo.highini.type.*
import java.io.*

open class Parser(
    val accessible: Boolean = false,
) {
    val classParsers = mutableMapOf<Class<*>, (value: String) -> Any>()
    val tags = mutableListOf<ConfigTag>()
    val includes = mutableListOf<ConfigTree>()

    lateinit var reader: HIniReader

    open fun startParse() = reader.startParse()

    open fun parseTree(
        hini: BufferedReader,
        file: File? = null
    ): ConfigTree = reader.parseTree(hini, file)

    open fun endParse() = reader.endParse()
}