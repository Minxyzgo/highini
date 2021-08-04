package com.github.minxyzgo.highini.exception

import com.github.minxyzgo.highini.type.*
@Suppress("unused")
open class ParseException : Exception {
    companion object {
        lateinit var currentTree: ConfigTree
        var currentLine: Int = -1
        var currentConfig: Config? = null

        fun ConfigValue.setStackTrace() {
            currentTree = parent.parent!!
            currentConfig = this
            currentLine = -1
        }

        fun ConfigSection.setStackTrace() {
            currentTree = parent!!
            currentConfig = this
            currentLine = -1
        }

        fun ConfigTree.setStackTrace(line: Int) {
            currentTree = this
            currentConfig = null
            currentLine = line
        }

        private fun stackTrace(msg: String): String {
            return "$msg (on ${
                when(currentConfig) {
                    is ConfigSection -> "section: ${(currentConfig as ConfigSection).name},"
                    is ConfigValue -> "value: ${(currentConfig as ConfigValue).name},"
                    else -> ""
                }
            } file: ${
                currentTree.file?.name
                    ?: "<Unknown.hini>"
            } line ${currentConfig?.line ?: currentLine})"
        }
    }

    constructor(msg: String) : super(stackTrace(msg))
    constructor(msg: String, e: Throwable) : super(stackTrace(msg), e)
    constructor(e: Throwable) : super(e)
}