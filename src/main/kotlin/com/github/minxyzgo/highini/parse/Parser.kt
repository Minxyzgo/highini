package com.github.minxyzgo.highini.parse

import com.github.minxyzgo.highini.exception.*
import com.github.minxyzgo.highini.exception.ParseException.Companion.setStackTrace
import com.github.minxyzgo.highini.func.*
import com.github.minxyzgo.highini.type.*
import com.github.minxyzgo.highini.util.*
import jdk.nashorn.internal.runtime.regexp.joni.*
import java.io.*
import java.lang.reflect.*
import java.util.*


open class Parser(val accessible: Boolean = false) {
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
        if(includes.hasTheSameName() || configTreeList.hasTheSameName())
            throw ParseException("There cannot be trees with the same name in the external library.")
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
            v.section.extends()
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
        var baseName: String? = null
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
            parseFinal(tagStr)
            name = parseExtends(tag_[1].trim())

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

    protected open fun parseValue(
        section: ConfigSection,
        value: ConfigValue,
    ) {
        println("parsing value...")
        value.setStackTrace()

        section.tag?.run {
            reader?.let {
                it(section, value.name, value.stringValue, value.line)
            }?.let { v ->
                value.value = v
                return@parseValue
            }
        }

        section.tag?.clazz?.let {
            val fieldMap = it.allFieldMap()
            val field = fieldMap[value.name]
            if(field != null) {
                val v = internalParse(field.type, section.parent!!, value.stringValue, value.line)
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
        } else if(value.stringValue.startsWith("::")) {
            value.value = parseReferenceValue(
                section.parent!!,
                value.stringValue,
                value.line
            )
        } else if(value.stringValue.startsWith("if")) {
            value.value = parseBoolean(
                value.stringValue,
                section.parent!!,
                value.line
            )
        } else if(value.stringValue.contains(Token.stringTemplate)) {
            value.value = parseStringTemplate(section.parent!!, value.stringValue, value.line)
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <T> parseList(tree: ConfigTree, value: String, line: Int): List<T> {
        tree.setStackTrace(line)
        println("value : $value")
        if (value.startsWith("[")) {
            if (!value.endsWith("]")) throw SyntaxException("Missing ] after list.")
            val message = value.removePrefix("[").removeSuffix("]").split(",")
            println("msg $message")
            return message.map {
                it.trim().run {
                    (parseReferenceValue(tree, this, line) ?: it) as T
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
                val tryParseKey = parseReferenceValue(tree, k, line)
                val tryParseValue = parseReferenceValue(tree, v, line)
                ((tryParseKey ?: k) as K) to ((tryParseValue ?: v) as V)
            }
        } else {
            throw SyntaxException("Missing { before map.")
        }
    }


    protected open fun internalParse(
        type: Class<*>,
        tree: ConfigTree,
        value: String,
        line: Int
    ): Any? {
        return parseReferenceValue(tree, value, line) ?: when(type) {
            List::class.java -> parseList<String>(tree, value, line)
            Array::class.java -> parseList<String>(tree, value, line).toTypedArray()
            Map::class.java -> parseMap<String, String>(tree, value, line)
            Boolp::class.java -> parseBoolean(value, tree, line)
            String::class.java -> parseStringTemplate(
                tree, value, line
            )
            else -> null
        } ?: when (type) {
            Int::class.java -> value.toInt()
            Long::class.java -> value.toLong()
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

    protected fun parseStringTemplate(
        tree: ConfigTree,
        value: String,
        line: Int,
        defaultSection: ConfigSection? = null
    ): String {
        var result = value
        for(input in Token.stringTemplate.findAll(value)) {
            val v = input.value
            val str = parseGetReference(
                v
                    .removePrefix("\${")
                    .removeSuffix("}"),
                tree,
                line,
                defaultSection
            ).value!!().toString()
            result = result.replace(v, str)
        }
        return result
    }

    protected fun parseReferenceValue(
        tree: ConfigTree,
        stringValue: String,
        line: Int
    ): Any? {
        return if(stringValue.startsWith("::"))
            parseGetReference(stringValue.removePrefix(("::")), tree, line).value!!()
        else
            null
//
//        val iterable = stringValue.iterator()
//        val list = mutableListOf<TemporaryReference>()
//        var parseString = stringValue
//        var lastValue = ""
//        var inRef = false
//        println("stringValue $stringValue")
//        while(iterable.hasNext()) {
//            when(val last = iterable.nextChar()) {
//                ':' -> {
//                    val next = iterable.nextChar()
//                    if(next == ':') {
//                        inRef = true
//                        lastValue += "::"
//                    } else if(inRef) {
//                        lastValue += ':'
//                    }
//                }
//
//                in 'A'..'Z', in '0'..'9', in 'a'..'z', '.', '[', ']' -> {
//                    if(inRef) lastValue += last
//                }
//
//                else -> {
//                    if(inRef) {
//                        if(lastValue.isBlank()) continue
//                        val reference = parseGetReference(
//                            lastValue.removePrefix("::").trimEnd(),
//                            tree,
//                            line
//                        )
//
//                        parseString = parseString.replace(lastValue.trimEnd(), reference.value!!().toString())
//                        list.add(reference)
//                        lastValue = ""
//                        inRef = false
//                    }
//                }
//
//
//            }
//        }
//        println("lastValue: $lastValue")
//        if(lastValue.isNotBlank() && inRef) {
//            val reference = parseGetReference(
//                lastValue.removePrefix("::"),
//                tree,
//                line
//            )
//            list.add(reference)
//            parseString = parseString.replace(lastValue, reference.value!!().toString())
//        }
//
//        action(parseString, list.toList())
    }



    protected open fun parseGetReference(
        key: String,
        configTree: ConfigTree,
        line: Int,
        defaultSection: ConfigSection? = null
    ): TemporaryReference {
        configTree.setStackTrace(line)
        val temporaryReference = TemporaryReference()
        println("key: $key")
        val reference = key.split(".").iterator()
        var section: ConfigSection? = null
        val first = reference.next()
        if(defaultSection == null || first != defaultSection.name) {
            for (tree in configTreeList) {
                if (tree[first] != null) {
                    section = tree[first]
                    break
                }
            }

            if (section == null) {
                val _tree = includes.firstOrNull { it.name == first }
                section = _tree?.get(reference.next())
            }

            if (section == null) throw UnresolvedReferenceException("Unknown section ${first}.")
        } else {
            section = defaultSection
        }
        if(!reference.hasNext()) return TemporaryReference({ section }, parentSection = section)
        var lastValue: Any? = null
        var isFirst = true
        println("continue")
        defaultSection?.mutableMap?.values?.forEach {
            println("get key ${it.name} value ${it.value}")
        }
        while(reference.hasNext()) {
            val baseKey = reference.next()
            fun isLast() = !reference.hasNext()
            fun parseConfigValueRef(it: ConfigValue, keyStr: String) {
                val v = if(it.stringValue.contains(Token.stringTemplate)) {
                    defaultSection?.let { se ->
                        parseStringTemplate(
                            (lastValue as ConfigSection).parent!!,
                            se.stringValue,
                            se.line,
                            defaultSection
                        )
                    } ?: it.value
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
                        println("fieldMap $fieldMap")
                        (fieldMap.values.firstOrNull { it.name == keyStr }
                            ?: throw UnresolvedReferenceException("field: $keyStr is not defined."))
                            .also {
                                if(accessible) it.isAccessible = true
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
                val v = internalParse(result.field!!.type, tree, value, line)
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
        line: Int,
        defaultSection: ConfigSection? = null
    ): Boolp {
        if(!value.startsWith("if")) throw SyntaxException("syntax error.")
        val split = value.removePrefix("if").iterator()
        tree.setStackTrace(line)

        val list = mutableListOf<String>()

        val stack = Stack<String>()
        val output = mutableListOf<Any>()
        var last = ""
        fun add() {
            println("last $last")
            if(last.isNotBlank()) {
                list.add(last)

                last = ""
            }
        }
        while(split.hasNext()) {
            when(val next = split.next()) {
                ' ' -> add()

                '&' -> {
                    val _next = split.next()
                    if(_next == '&') {
                        add()
                        list.add("and")
                    } else {
                        last += '&'
                        last += _next
                    }
                }

                '|' -> {
                    val _next = split.next()
                    if(_next == '|') {
                        add()
                        list.add("or")
                    } else {
                        last += '|'
                        last += _next
                    }
                }

                '>' -> {
                    add()
                    val _next = split.next()
                    if(_next == '=') {
                        list.add(">=")
                    } else {
                        list.add(">")
                        last += _next
                    }
                }

                '<' -> {
                    add()
                    val _next = split.next()
                    if(_next == '=') {
                        list.add("<=")
                    } else {
                        list.add("<")
                        last += _next
                    }
                }

                '!' -> {
                    add()
                    val _next = split.next()
                    if(_next == '=') {
                        list.add("!=")
                    } else {
                        list.add("not")
                        last += _next
                    }
                }

                '=' -> {
                    add()
                    val _next = split.next()
                    if(_next == '=') {
                        list.add("==")
                    } else {
                        throw SyntaxException("syntax error.")
                    }
                }

                '(' -> {
                    add()
                    list.add("(")
                }

                ')' -> {
                    add()
                    list.add(")")
                }

                else -> {
                    last += next
                }
            }
        }

        if(last.isNotBlank()) {
            add()
        }

        println("output $output")
        println("list $list")

        for(str in list) {
            val op = Operation.values().firstOrNull { it.name == str || it.code == str }
            when {
                op != null -> {
                    if (stack.isEmpty() || "(" == stack.peek() || op.priority > Operation.valueOf(stack.peek()).priority) {
                        stack.push(op.name)
                    } else {
                        while (!stack.isEmpty() && "(" != stack.peek()) {
                            if (op.priority <= Operation.valueOf(stack.peek()).priority) {
                                output.add(stack.pop())
                            }
                        }
                        stack.push(op.name)
                    }
                }

                str == "(" -> {
                    stack.push("(")
                }

                str == ")" -> {
                    while(!stack.isEmpty()) {
                        if("(" == stack.peek()) {
                            stack.pop()
                            break
                        } else {
                            output.add(stack.pop())
                        }
                    }
                }

                else -> {
                    when {
                        str.contains(".") -> {
                            val result = parseGetReference(
                                str,
                                tree,
                                line,
                                if(
                                    str.split(".")[0]
                                    == defaultSection?.name
                                ) defaultSection else null
                            )
                            output.add(Prov { result.value!!() })
                        }

                        else -> {
                            output.add(parseReferenceValue(tree, str, line) ?: str)
                        }
                    }
                }
            }
        }

        while(!stack.isEmpty()) {
            output.add(stack.pop())
        }

        println(value)
        println(list)
        println(output)


        return Boolp {
            val outStack = Stack<Any>()
            for(out in output) {
                val op = Operation.values().firstOrNull { it.name == out.toString() }
                if(op == null) {
                    when(out) {
                        is Prov<*> -> outStack.push(out.get())
                        else -> outStack.push(out)
                    }
                } else {
                    if(op == Operation.not) {
                        if(outStack.size == 0) throw SyntaxException("syntax error.")
                        val first = outStack.pop()
                        outStack.push(op.parser(
                            first.toString(),
                            ""
                        ))
                    } else {
                        println(outStack)
                        if(outStack.size < 2) throw SyntaxException("syntax error.")
                        val second = outStack.pop()
                        val first = outStack.pop()
                        println("first $first second $second")
                        outStack.push(
                            op.parser(
                                first.toString(),
                                second.toString()
                            )
                        )
                    }
                }
            }

            if(outStack.size > 1) throw SyntaxException("syntax error.")
            outStack.pop().toString().toBoolean()
        }
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