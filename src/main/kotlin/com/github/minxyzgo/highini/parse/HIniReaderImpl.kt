package com.github.minxyzgo.highini.parse

import com.github.minxyzgo.highini.exception.*
import com.github.minxyzgo.highini.exception.ParseException.Companion.setStackTrace
import com.github.minxyzgo.highini.func.*
import com.github.minxyzgo.highini.type.*
import com.github.minxyzgo.highini.util.*
import java.io.*

internal class HIniReaderImpl(
    parser: Parser
): BaseHIniReader(parser) {
    protected val cacheSection: MutableMap<String, TemporarySection> = mutableMapOf()
    protected val cacheReferenceKeyValue: MutableList<TemporaryValue> = mutableListOf()
    protected val configTreeList: MutableList<ConfigTree> = mutableListOf()


    override fun startParse() {
        cacheSection.clear()
        cacheReferenceKeyValue.clear()
        configTreeList.clear()
    }

    override fun parseTree(hini: BufferedReader, file: File?): ConfigTree {
        val text = hini.readText()
        val tree = ConfigTree(text, file = file)
        val list = text.split("\n")
        var currentSection: ConfigSection? = null

        list.forEachIndexed { i, str ->
            val index = i + 1
            if(str.isBlank()) return@forEachIndexed
            var sectionString = str.trim().replace("#", " # ")
            val commitList = sectionString.split("#")
            if(commitList.size > 1) sectionString = commitList[0]
            if(sectionString.isBlank()) return@forEachIndexed
            tree.setStackTrace(index)
            if(index == 1) {
                if(sectionString.startsWith("${Keyword.Export.codeName()} ")) {
                    tree.name = sectionString.split(" ", limit = 2)[1]
                    return@forEachIndexed
                }
            }
            if(sectionString.startsWith("[")) {
                if(!sectionString.endsWith("]")) throw SyntaxException("Missing ] after section.")
                val message = sectionString.removePrefix("[").removeSuffix("]")
                currentSection = parseSection(tree, message, index, sectionString)

            } else if(sectionString.trim().contains(Token.keyValue)) {
                var valueString = sectionString.trim()
                var isFinal = false
                if(valueString.startsWith("${Keyword.Final.codeName()} ")) {
                    valueString = valueString.removePrefix("${Keyword.Final.codeName()} ")
                    isFinal = true
                }

                for(keyword in Keyword.values()) {
                    if(valueString.contains(keyword.codeName())) throw KeywordException(keyword)
                }


                val keyValue = if(
                    valueString.indexOf('=')
                        .run {
                            this != -1 && valueString.indexOf(":")
                                .let { t -> t == -1 || this < t  } }
                ) valueString.split("=", limit = 2) else valueString.split(":", limit = 2)
                val value = keyValue[1]

                if(keyValue[0].contains(".")) {
                    cacheReferenceKeyValue.add(
                        TemporaryValue(
                            keyValue[0].trim(),
                            value.trim(),
                            tree,
                            index,
                            isFinal
                        )
                    )
                    return@forEachIndexed
                }
                val key = keyValue[0].trim()
                if(currentSection == null) throw UnresolvedReferenceException("Unknown current section.")
                currentSection!![key] = ConfigValue(key, value.trim(), value.trim(), index, currentSection!!, isFinal)
            } else {
                throw SyntaxException("syntax error.")
            }
        }

        hini.close()
        configTreeList.add(tree)
        return tree
    }

    override fun endParse() {
        if(parser.includes.hasTheSameName() || configTreeList.hasTheSameName())
            throw ParseException("There cannot be trees with the same name in the external library.")
        cacheSection.forEach { (_, v) ->
            val k = v.extendsSection
            v.section.setStackTrace()
            var section: ConfigSection? = null
            for (tree in configTreeList) {
                if (tree[k] != null) {
                    section = tree[k]
                    break
                }
            }

            if (section == null) {
                section = cacheSection[k]?.section ?: run {
                    var result: ConfigSection? = null
                    for (tree in parser.includes) {
                        if (tree[k] != null) {
                            result = tree[k]
                            break
                        }
                    }
                    result
                }
            }

            if (section == null) throw UnresolvedReferenceException("extends section: $k is not defined.")
            v.section.extendsSection = section
            if (section.tag != v.section.tag && section.tag?.clazz?.isAssignableFrom(
                    v.section.tag?.clazz ?: Object::class.java
                ).run { this == null || this == false}) throw ParseException("Cannot extends a section with a different tag.")
            section.children.add(v.section)
        }

        cacheSection.forEach { (_, v) ->
            v.section.extends()
        }

        val necessaryTags = parser.tags.filter { it.isNecessary }.map { it.name }.toMutableSet()
        configTreeList.forEach { tree ->
            tree.mutableMap.forEach { (_, section) ->
                section.mutableMap.forEach { (_, value) ->
                    parseValue(section, value)
                }

                section.tag?.name.let { if(it in necessaryTags) necessaryTags.remove(it) }
            }
        }

        if(necessaryTags.size > 0) throw ParseException("necessaryTags: $necessaryTags must use.")

        cacheReferenceKeyValue.forEach { (k, v, tree, line) ->
            parseSetReference(k, v, tree, line)
        }
    }

    /*
    There is some confusion and will be rewritten in the future
     */
    override fun parseSection(
        tree: ConfigTree,
        message: String,
        index: Int,
        sectionString: String
    ): ConfigSection {
        tree.setStackTrace(index)
        val tag_ = message.split(":", limit = 2)
        var tag: ConfigTag? = null
        var baseName: String? = null
        var isFinal = false
        var name: String? = null
        var extendsSection: String? = null

        fun parseExtends(str: String): String {
            return if(str.contains(" ${Keyword.Extends.codeName()} ")) {
                val str_ = str.split(" ${Keyword.Extends.codeName()} ", limit = 2)
                extendsSection = str_[1].trim()
                str_[0].trim()
            } else {
                str.trim()
            }
        }

        fun parseFinal(str: String): String {
            return if(str.startsWith("${Keyword.Final.codeName()} ")) {
                isFinal = true
                str.removePrefix("${Keyword.Final.codeName()} ")
            } else {
                str
            }
        }

        if(tag_.size > 1) {
            val tagStr = tag_[0].trim()
            parseFinal(tagStr)
            name = parseExtends(tag_[1].trim())

            if(tagStr.isBlank()) {
                name = parseExtends(tag_[1].trim())
                tag = parser.tags.firstOrNull{ name == it.name }
                if(tag != null) {
                    if(!tag.isSingle) throw ParseException("tag ${tag.name} is not isSingle.")
                }
            } else {
                tag = parser.tags.firstOrNull { tagStr == it.name }
                if(tag?.isSingle == true) {
                    throw ParseException("the single tag ${tag.name} is not allowed here.")
                }
            }
            if(tag == null) throw UnresolvedReferenceException("Unknown tag.")
        } else {
            baseName = message
        }



        name = name ?: parseExtends(parseFinal(baseName!!))
        if(extendsSection == name) throw ParseException("Can't make section extends itself.")
        Keyword.values().forEach {
            if(name.contains(it.codeName()))
                throw KeywordException(it)
        }

        if(tree[name] != null) throw ParseException("The tree cannot have a section with the same name.")
        println("name : $name")
        val section = ConfigSection(
            name,
            sectionString,
            isFinal = isFinal,
            line = index,
            parent = tree,
            tag = tag
        ).apply {
            setStackTrace()
        }
        tag?.children?.add(section)
        if(extendsSection != null) {
            cacheSection[name] = TemporarySection(
                extendsSection!!,
                index,
                section
            )

            tree[name] = section
        } else {
            tree[name] = section
        }

        return section
    }

    override fun parseGetReference(
        key: String,
        configTree: ConfigTree,
        line: Int,
        defaultSection: List<ConfigSection>?
    ): TemporaryReference {
        configTree.setStackTrace(line)
        val temporaryReference = TemporaryReference()
        val reference = key.split(".").iterator()
        var section: ConfigSection? = null
        val first = reference.next()

        val _defaultSection = defaultSection?.firstOrNull { it.name == first }
        if(_defaultSection == null) {
            for (tree in configTreeList) {
                if (tree[first] != null) {
                    section = tree[first]
                    break
                }
            }

            if (section == null) {
                val _tree = parser.includes.firstOrNull { it.name == first }
                section = _tree?.get(reference.next())
            }

            if (section == null) throw UnresolvedReferenceException("Unknown section ${first}.")
        } else {
            section = _defaultSection
        }

        if(!reference.hasNext()) return TemporaryReference({ section }, parentSection = section)
        var lastValue: Any? = null
        var isFirst = true

        while(reference.hasNext()) {
            val baseKey = reference.next()
            fun isLast() = !reference.hasNext()
            fun parseConfigValueRef(it: ConfigValue, keyStr: String) {
                val v = if(it.stringValue.contains(Token.stringTemplate)) {
                    parseStringTemplate(
                        it.parent.parent!!,
                        it.stringValue,
                        it.line,
                        defaultSection
                    )
                } else if(it.value is Boolp && defaultSection != null) {
                    parseBoolean(it.stringValue, (lastValue as ConfigSection).parent!!, it.line, defaultSection).get()
                } else if(it.value is Prov<*>) {
                    (it.value as Prov<*>).get()
                } else {
                    it.value
                }
                if(isLast()) {
                    with(temporaryReference) {
                        value = {
                            v
                        }
                        this.index = keyStr
                        parent = section
                    }
                } else {
                    lastValue = v
                }
            }
            fun parseRef(keyStr: String) {
                if(isFirst) {
                    (section[keyStr]
                        ?: throw UnresolvedReferenceException("Unknown keyValue $baseKey."))
                        .also {
                            parseConfigValueRef(it, keyStr)
                        }
                    isFirst = false
                }
                else when(lastValue) {
                    is ConfigSection -> {
                        (lastValue as ConfigSection)[keyStr]!!.also {
                            parseConfigValueRef(it, keyStr)
                        }
                    }

                    else -> {
                        val fieldMap = lastValue!!::class.java.apply{ println(this.name) }.allFieldMap()

                        (fieldMap.values.firstOrNull { it.name == keyStr }
                            ?: throw UnresolvedReferenceException("field: $keyStr is not defined."))
                            .also {
                                if(parser.accessible) it.isAccessible = true
                                if(isLast()) temporaryReference.field = it
                            }
                            .get(lastValue)
                            .also {
                                if(isLast()) {
                                    with(temporaryReference) {
                                        parent = lastValue
                                        value = { it }
                                    }
                                } else {
                                    lastValue = it
                                }
                            }
                    }
                }
            }

            //such as xxx[key1][key2][key3]
            if(baseKey.indexOf("[").run { this != -1 && this < baseKey.indexOf("]")}) {
                val strList = mutableListOf<String>()
                var lastStr = ""
                var inRef = false
                for(k in baseKey) {
                    when(k) {
                        '[' -> {
                            if(lastStr.isNotBlank() && !inRef) strList.add(lastStr)
                            if(inRef) continue
                            inRef = true
                            lastStr = ""
                        }
                        ']' -> {
                            if(lastStr.isNotBlank() && inRef) strList.add(lastStr)
                            if(!inRef) throw SyntaxException("Missing [ in the reference.")
                            inRef = false
                        }

                        else -> lastStr += k
                    }
                }

                val base = strList[0]
                println("rm base: $baseKey list : $strList")
                strList.removeFirst()
                parseRef(base)
                parseListOrMap(lastValue!!, strList).run {
                    if(isLast()) {
                        with(temporaryReference) {
                            this.value = { second }
                            this.index = first
                            this.parent = lastValue
                            return@run
                        }
                    }
                    lastValue = second
                }
            } else {
                parseRef(baseKey)
            }
        }
        temporaryReference.parentSection = section
        return temporaryReference
    }
}