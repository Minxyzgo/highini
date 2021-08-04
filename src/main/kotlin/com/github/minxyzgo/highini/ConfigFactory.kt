package com.github.minxyzgo.highini

import com.github.minxyzgo.highini.parse.*
import com.github.minxyzgo.highini.type.*
import java.io.*
import java.net.*

object ConfigFactory {
    fun parseResources(url: URL) = parseResources(url, Parser())

    fun parseResources(url: URL, parser: Parser): ConfigTree {
        parser.startParse()
        val result = parser.parseTree(
            url.openStream().bufferedReader(),
            File(url.path)
        )

        parser.endParse()
        return result
    }
}