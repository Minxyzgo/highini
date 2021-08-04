package com.github.minxyzgo.highini.parse

import com.github.minxyzgo.highini.exception.*
import com.github.minxyzgo.highini.exception.ParseException.Companion.setStackTrace
import com.github.minxyzgo.highini.func.*
import com.github.minxyzgo.highini.type.*
import com.github.minxyzgo.highini.util.*
import java.io.*
import java.lang.IllegalStateException
import java.lang.reflect.*
import java.util.*

open class Parser {
    val classParsers = mutableMapOf<Class<*>, (value: String) -> Any>()
    val tags = mutableListOf<ConfigTag>()
    val includes = mutableListOf<ConfigTree>()

    protected val cacheSection: MutableMap<String, TemporarySection> = mutableMapOf()
    protected val cacheReferenceKeyValue: MutableList<TemporaryValue> = mutableListOf()
    protected val configTreeList: MutableList<ConfigTree> = mutableListOf()

    open fun startParse() {
        cacheSection.clear()
        cacheReferenceKeyValue.clear()
        configTreeList.clear()
    }

    open fun parseTree(hini: BufferedReader, file: File? = null): ConfigTree {
        val text = hini.readText()
        val tree = ConfigTree(text, file = file)
        val list = text.split("\n")
        var currentSection: ConfigSection? = null

        list.forEachIndexed { i, str ->
            val index = i + 1
            println("parsing new line...")
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

    open fun endParse() {
        println("end parse")
        cacheSection.forEach { (_, v) ->
            println("parse cache 1")
            val k = v.extendsSection
            v.section.setStackTrace()
            println("extends key : $k")
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
                    for (tree in includes) {
                        if (tree[k] != null) {
                            result = tree[k]
                            break
                        }
                    }
                    result
                }
            }

            if (section == null) throw UnresolvedReferenceException("extends section: $k is not defined.")
            v.section.extendsSection = section.apply { println("finally extends: ${this.name}") }

            if (section.tag != v.section.tag && section.tag?.clazz?.isAssignableFrom(
                    v.section.tag?.clazz ?: Object::class.java
                ).run { this == null || this == false}) throw ParseException("Cannot extends a section with a different tag.")
            section.children.add(v.section)
        }

        cacheSection.forEach { (_, v) ->
            println("parse cache 2")
            var superSection = v.section.extendsSection!!.apply { println("base: $name") }
            val cache = mutableListOf<ConfigSection>()
            while(superSection.extendsSection != null && !superSection.isExtended) {
                if(superSection in cache) {
                    superSection.setStackTrace()
                    throw ParseException("Cyclic inheritance.")
                }
                superSection = superSection.extendsSection!!.apply { println("name: $name") }
                cache.add(superSection)
                println("while")
            }
            v.section.setStackTrace()
            if(superSection.isFinal) throw ParseException("Cannot extends a final section.")

            fun ConfigSection.forEachChildren(): Unit = this.children.forEach loop@{ child: ConfigSection ->
                if(child.isExtended) return
                println("for each child : ${child.name}")
                this.mutableMap.forEach { (key, value) ->
                    if(child[key] != null && value.isFinal) throw ParseException("Cannot override a final value.")
                    if(child[key] == null) child[key] = value
                }
                child.isExtended = true
                if(child.children.isNotEmpty()) child.forEachChildren()
            }

            superSection.forEachChildren()
        }

        val necessaryTags = tags.filter { it.isNecessary }.map { it.name }.toMutableSet()
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

    protected open fun parseSection(
        tree: ConfigTree,
        message: String,
        index: Int,
        sectionString: String
    ): ConfigSection {
        tree.setStackTrace(index)
        val tag_ = message.split(":", limit = 2)
        var tag: ConfigTag? = null
        val baseName: String
        var isFinal = false
        var name: String? = null
        var extendsSection: String? = null

        fun parseExtends(str: String): String {
            return if(str.contains(" ${Keyword.Extends.codeName()} ")) {
                val str_ = str.split(" ${Keyword.Extends.codeName()} ", limit = 2)
                println(str_)
                extendsSection = str_[1].trim().apply { println("extends: $this") }
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
            println("tagStr: $tagStr")

            name = parseExtends(parseFinal(tagStr))

            if(tagStr.isBlank()) {
                name = parseExtends(tag_[1].trim())
                tag = tags.firstOrNull{ name == it.name }
                if(tag != null) {
                    if(!tag.isSingle) throw ParseException("tag ${tag.name} is not isSingle.")
                }
            } else {
                tag = tags.firstOrNull { tagStr == it.name }
                if(tag?.isSingle == true) {
                    throw ParseException("the single tag ${tag.name} is not allowed here.")
                }
            }
            if(tag == null) throw UnresolvedReferenceException("Unknown tag.")
            baseName = tag_[1].trim()
        } else {
            baseName = message
        }



        name = name ?: parseExtends(parseFinal(baseName))
        if(extendsSection == name) throw ParseException("Can't make section extends itself.")
        Keyword.values().forEach {
            if(name.contains(it.codeName()))
                throw KeywordException(it)
        }

        if(tree[name] != null) throw ParseException("The tree cannot have a section with the same name.")

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

    protected open fun parseValue(
        section: ConfigSection,
        value: ConfigValue,
    ) {
        println("parsing value...")
        value.setStackTrace()

        section.tag?.run {
            reader?.let {
                it(section, value.name, value.stringValue)
            }?.let { v ->
                value.value = v
                return@parseValue
            }
        }

        section.tag?.clazz?.let {
            val fieldMap = it.allFieldMap()
            val field = fieldMap[value.name]
            if(field != null) {
                val v = internalParse(field.type, section.parent!!, value.name, value.stringValue, value.line)
                if(v != null) {
                    value.value = v
                } else {
                    val parser = classParsers[field.type]
                        ?: throw UnsupportedOperationException("Unknown class type: ${field.type.name}.")
                    value.value = parser(value.stringValue)
                }
            }
        }

        if(value.stringValue.contains(Token.allSection)) {
            value.value = parseList<String>(
                section.parent!!,
                value.stringValue,
                value.line
            )
        } else if(value.stringValue.contains(Token.map)) {
            value.value = parseMap<String, String>(
                section.parent!!,
                value.stringValue,
                value.line
            )
        } else if(value.stringValue.contains("::")) {
            value.value = parseStringReference(
                section.parent!!,
                value.stringValue,
                value.line
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <T> parseList(tree: ConfigTree, value: String, line: Int): List<T> {
        tree.setStackTrace(line)
        if (value.startsWith("[")) {
            if (!value.endsWith("]")) throw SyntaxException("Missing ] after list.")
            val message = value.removePrefix("[").removeSuffix("]").split(",")
            return message.map {
                it.trim().run {
                    (parseStringReference(tree, value, line) ?: it) as T
                }
            }
        } else {
            throw SyntaxException("Missing [ before list.")
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <K, V> parseMap(tree: ConfigTree, value: String, line: Int): Map<K, V> {
        tree.setStackTrace(line)
        if (value.startsWith("{")) {
            if (!value.endsWith("}")) throw SyntaxException("Missing } after map.")
            val message = value.removePrefix("{").removeSuffix("}").split(",")
            return message.map { it.trim() }.associate {
                val split = it.split(":", limit = 2)
                if(split.size < 2) throw SyntaxException("Missing : in the map.")
                val k = split[0].trim()
                val v = split[1].trim()
                val tryParseKey = parseStringReference(tree, k, line)
                val tryParseValue = parseStringReference(tree, v, line)
                ((tryParseKey ?: k) as K) to ((tryParseValue ?: v) as V)
            }
        } else {
            throw SyntaxException("Missing { before map.")
        }
    }


    protected open fun internalParse(
        type: Class<*>,
        tree: ConfigTree,
        key: String,
        value: String,
        line: Int
    ): Any? {
        return when(type) {
            List::class.java -> parseList<String>(tree, value, line)
            Array::class.java -> parseList<String>(tree, value, line).toTypedArray()
            Map::class.java -> parseMap<String, String>(tree, value, line)
            else -> null
        } ?:
        parseStringReference(tree, value, line) ?: when (type) {
            Int::class.java -> value.toInt()
            Long::class.java -> value.toLong()
            String::class.java -> value
            Char::class.java -> {
                if (value.length > 1)
                    throw IllegalStateException("$value is not a char.")
                else
                    value
            }

            Float::class.java -> value.toFloat()
            Boolean::class.java -> value.toBoolean()
            Byte::class.java -> value.toByte()
            Double::class.java -> value.toDouble()
            Short::class.java -> value.toShort()
            UInt::class.java -> value.toInt()
            ULong::class.java -> value.toULong()
            UByte::class.java -> value.toUByte()
            UShort::class.java -> value.toUShort()
            else -> null
//            else -> {
//                }
        }
    }


    protected fun parseStringReference(
        tree: ConfigTree,
        value: String,
        line: Int
    ): Any? {
        if(value.contains("::")) {
            //var list: List<TemporaryReference>? = null
            var str: String? = null
            parseReferenceValue(
                tree,
                value,
                line
            ) { s, _ ->
               // list = l
                str = s
            }

//            if(list!!.size > 1) {
//                return str!!
//            } else {
//                return list!!.first().value!!
//            }
            return str!!
        } else {
            return null
        }
    }

    protected fun parseReferenceValue(
        tree: ConfigTree,
        stringValue: String,
        line: Int,
        action: (String, List<TemporaryReference>) -> Unit
    ) {

        val iterable = stringValue.iterator()
        val list = mutableListOf<TemporaryReference>()
        var parseString = stringValue
        var lastValue = ""
        var inRef = false

        while(iterable.hasNext()) {
            when(val last = iterable.nextChar()) {
                ':' -> {
                    val next = iterable.nextChar()
                    if(next == ':') {
                        inRef = true
                        lastValue += "::"
                    } else if(inRef) {
                        lastValue += ':'
                    }
                }

                in 'A'..'Z', in '0'..'9', in 'a'..'z', '.', '[', ']' -> {
                    if(inRef) lastValue += last
                }

                else -> {
                    if(inRef) {
                        if(lastValue.isBlank()) continue
                        val reference = parseGetReference(
                            lastValue.removePrefix("::").trimEnd(),
                            tree,
                            line
                        )

                        parseString = parseString.replace(lastValue.trimEnd(), reference.value.toString())
                        list.add(reference)
                        lastValue = ""
                        inRef = false
                    }
                }


            }
        }
        println("lastValue: $lastValue")
        if(lastValue.isNotBlank() && inRef) {
            val reference = parseGetReference(
                lastValue.removePrefix("::"),
                tree,
                line
            )
            list.add(reference)
            parseString = parseString.replace(lastValue, reference.value.toString())
        }

        action(parseString, list.toList())
    }

    protected open fun parseGetReference(
        key: String,
        configTree: ConfigTree,
        line: Int
    ): TemporaryReference {
        configTree.setStackTrace(line)
        val temporaryReference = TemporaryReference()
        println("key: $key")
        val reference = key.split(".").iterator()
        var section: ConfigSection? = null
        val first = reference.next()
        for(tree in configTreeList) {
            if(tree[first] != null) {
                section = tree[first]
                break
            }
        }

        if(section == null) {
            val _tree = includes.firstOrNull { it.name == first }
            section = _tree?.get(reference.next())
        }

        if(section == null) throw UnresolvedReferenceException("Unknown section ${first}.")
        if(!reference.hasNext()) return TemporaryReference({ section }, parentSection = section)
        var lastValue: Any? = null
        var isFirst = true
        while(reference.hasNext()) {
            val baseKey = reference.next()
            fun isLast() = !reference.hasNext()
            fun parseRef(keyStr: String) {
                lastValue = if(isFirst) {
                    (section[keyStr]?.value
                        ?: throw UnresolvedReferenceException("Unknown keyValue $baseKey."))
                        .also {
                            if(isLast()) {
                                with(temporaryReference) {
                                    value = { it }
                                    this.index = keyStr
                                    parent = section
                                }
                            }
                        }
                    isFirst = false
                }
                else when(lastValue) {
                    is ConfigSection -> {
                        (lastValue as ConfigSection)[keyStr].also {
                            if(isLast()) {
                                with(temporaryReference) {
                                    value = { it }
                                    this.index = keyStr
                                    parent = lastValue as ConfigSection
                                }
                            }
                        }
                    }

                    else -> {
                        val fieldMap = lastValue!!::class.java.allFieldMap()
                        (fieldMap.values.firstOrNull { it.name == keyStr }
                            ?: throw UnresolvedReferenceException("field: $keyStr is not defined."))
                            .also {
                                if(isLast()) temporaryReference.field = it
                            }
                            .get(lastValue)
                            .also {
                                if(isLast()) {
                                    with(temporaryReference) {
                                        parent = lastValue
                                        value = { it }
                                    }
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

    @Suppress("UNCHECKED_CAST")
    protected open fun parseSetReference(
        key: String,
        value: String,
        tree: ConfigTree,
        line: Int
    ) {
        val result = parseGetReference(key, tree, line)
        // In any case, it is always good to set it explicitly.
        tree.setStackTrace(line)

        when {
            result.field != null -> {
                val v = internalParse(result.field!!.type, tree, key, value, line)
                result.field!!.set(result.parent!!, v)
            }

            result.parent is MutableMap<*, *> -> {
                (result.parent as MutableMap<String, Any>)[result.index!!.toString()] = value as Any
            }

            else -> {
                val configSection = (result.parent as? ConfigSection) ?: result.parentSection!!
                val configValue = configSection[result.index!!.toString()]!!.apply {
                    if(this.isFinal) throw ParseException("Cannot set a final value.")
                    this.value = value
                }
                parseValue(configSection, configValue)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected fun parseBoolean(
        value: String,
        tree: ConfigTree,
        line: Int
    ): Boolp {
        tree.setStackTrace(line)

        val split = value
            .replace("&&", " && ")
            .replace("||", " || ")
            .split(" ")
            .filter { it.isNotBlank() }
            .iterator()
        var isAnd = false
        var isNot = false
        var isOr = false
      //  var isFirst = true
        var last: (() -> Any?)? = null

        val list = mutableListOf<(Boolean) -> Boolean>()

        fun add(boolp: () -> Boolean) {
            if(list.isEmpty()) {
                list.add {  (if(isNot) !boolp.invoke() else boolp.invoke()) }
            } else if(isAnd) {
                list.add { bool ->
                    bool && (if(isNot) !boolp.invoke() else boolp.invoke())
                }
                isAnd = false
            } else if(isOr) {
                list.add { bool ->
                    bool ||  (if(isNot) !boolp.invoke() else boolp.invoke())
                }
                isOr = false
            }
            if(isNot) isNot = false
        }
        /*
        while(split.hasNext()) {
            val str = split.next()
            when(str) {
                "and", "&&" -> {
                    if(last == null) throw SyntaxException("syntax error.")
                    else if(isAnd || isOr) throw SyntaxException("syntax error.")
                    else isAnd = true
                }

                "or", "||" -> {
                    if(last == null) throw SyntaxException("syntax error.")
                    else if(isAnd || isOr) throw SyntaxException("syntax error.")
                    else isOr = true
                }

                "not" -> {
                    if(isNot) throw SyntaxException("syntax error.")
                    else isNot = true
                }

                "in", "!in" -> {
                    val _next = split.next()
                    val v = last()
                    if(!last.contains(Token.number) || !_next.contains(Token.numberRange))
                        throw SyntaxException("syntax error.")
                   add {
                        val _next_range = _next.split("..")
                        val first = _next_range[0].toInt()
                        val second = _next_range[1].toInt()
                        str.toInt() in first..second
                    }
                    last = ""
                }

                else -> {
                    if(last.isNotBlank())
                       throw SyntaxException("syntax error.")
                    if(str.contains(Regex("""true|false"""))) {
                        val _isNot = isNot
                        add { str.toBoolean()  }
                    } else if(str.contains(".")) {
                        val result = parseGetReference(
                            str,
                            tree,
                            line
                        )

                        when(result.value()) {
                            is Number -> lastValue
                        }
                    }
                }
            }

            first = false
        }

         */
        return Boolp { true }
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
}