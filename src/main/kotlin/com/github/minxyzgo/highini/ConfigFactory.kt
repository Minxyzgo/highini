package com.github.minxyzgo.highini

import com.github.minxyzgo.highini.parse.*
import com.github.minxyzgo.highini.type.*
import java.io.*
import java.net.*

object ConfigFactory {
    @JvmOverloads
    fun parseResources(url: URL, parser: Parser = Parser()): ConfigTree {
        parser.startParse()
        val result = parser.parseTree(
            url.openStream().bufferedReader(),
            File(url.path)
        )

        parser.endParse()
        return result
    }

    @JvmOverloads
    fun parseFile(file: File, parser: Parser = Parser()): ConfigTree {
        parser.startParse()
        val result = parser.parseTree(file.bufferedReader(), file)
        parser.endParse()
        return result
    }

    @JvmOverloads
    fun parseFiles(
        files: List<File>,
        parser: Parser = Parser()
    ): List<ConfigTree> {
        val list = mutableListOf<ConfigTree>()
        parser.startParse()
        for(file in files) {
            list.add(
                parser.parseTree(file.bufferedReader(), file)
            )
        }
        parser.endParse()
        return list
    }

    @JvmOverloads
    fun parseString(
        string: String,
        parser: Parser = Parser()
    ): ConfigTree {
        parser.startParse()
        val result = parser.parseTree(
            string.reader().buffered()
        )
        parser.endParse()
        return result
    }
}