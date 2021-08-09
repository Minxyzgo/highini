package com.github.minxyzgo.highini.parse

import com.github.minxyzgo.highini.exception.*
import com.github.minxyzgo.highini.exception.ParseException.Companion.setStackTrace
import com.github.minxyzgo.highini.func.*
import com.github.minxyzgo.highini.type.*
import com.github.minxyzgo.highini.util.*
import java.util.*

/**
 * This class implements some basic analysis.
 */
@Suppress("UNCHECKED_CAST")
abstract class BaseHIniReader(
    override val parser: Parser
) : HIniReader {
    override fun parseValue(
        section: ConfigSection,
        value: ConfigValue,
    ) {
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
                    val parser = parser.classParsers[field.type]
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

    override fun internalParse(
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

    override fun <T> parseList(
        tree: ConfigTree,
        value: String,
        line: Int
    ): List<T> {
        tree.setStackTrace(line)
        if (value.startsWith("[")) {
            if (!value.endsWith("]")) throw SyntaxException("Missing ] after list.")
            val message = value.removePrefix("[").removeSuffix("]").split(",")
            return message.map {
                it.trim().run {
                    (parseReferenceValue(tree, this, line) ?: it) as T
                }
            }
        } else {
            throw SyntaxException("Missing [ before list.")
        }
    }

    override fun <K, V> parseMap(
        tree: ConfigTree,
        value: String,
        line: Int
    ): Map<K, V> {
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

    override fun parseBoolean(
        value: String,
        tree: ConfigTree,
        line: Int,
        defaultSection: List<ConfigSection>?
    ): Boolp {
        if(!value.startsWith("if")) throw SyntaxException("syntax error.")
        val split = value.removePrefix("if").iterator()
        tree.setStackTrace(line)

        /*
        This list stores the parsed characters.
        It contains values or operators,
        so it is not the final output
         */
        val list = mutableListOf<String>()

        // infix to suffix
        val stack = Stack<String>()
        val output = mutableListOf<Any>()
        var last = ""
        fun add() {
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
                        //Maybe it is a reference
                        str.contains(".") -> {
                            val result = parseGetReference(
                                str,
                                tree,
                                line,
                                defaultSection
                            )
                            output.add(Prov { result.value!!() })
                        }

                        //Or it isn't
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
                        if(outStack.size < 2) throw SyntaxException("syntax error.")
                        val second = outStack.pop()
                        val first = outStack.pop()
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

    override fun parseStringTemplate(
        tree: ConfigTree,
        value: String,
        line: Int,
        defaultSection: List<ConfigSection>?
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

    override fun parseReferenceValue(
        tree: ConfigTree,
        stringValue: String,
        line: Int
    ): Any? = if(stringValue.startsWith("::"))
        parseGetReference(stringValue.removePrefix(("::")), tree, line).value!!()
    else
        null

    override fun parseSetReference(
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
}