package com.github.minxyzgo.highini.util

import com.github.minxyzgo.highini.annotation.*
import com.github.minxyzgo.highini.exception.*
import com.github.minxyzgo.highini.exception.ParseException.Companion.setStackTrace
import com.github.minxyzgo.highini.func.Prov
import com.github.minxyzgo.highini.type.*
import java.io.*
import java.lang.reflect.*
import java.util.*

private val cacheFieldMap: MutableMap<Class<*>, Map<String, Field>> = mutableMapOf()

fun Class<*>.allFieldMap(): Map<String, Field>  {
    cacheFieldMap[this]?.let { return it }
    val fieldList = mutableListOf<Field>()
    var clazz = this
    while(clazz != Object::class.java && clazz != Any::class.java) {
        fieldList.addAll(clazz.declaredFields)
        clazz = clazz.superclass
    }

    return fieldList.associateBy { it.name }.also { cacheFieldMap[this] = it }
}

fun emptyTree(string: String = ""): ConfigTree {
    return ConfigTree(string).apply {
        this.setStackTrace(0)
    }
}

fun List<ConfigTree>.hasTheSameName(): Boolean {
    val allName = this.mapNotNull { it.name }
    return allName.toSet().size != allName.size
}

fun parseListOrMap(value: Any, str: List<String>): Pair<Any?, Any?> {
    return str.run {
        var last: Any? = value
        var index: Any? = null
        for(v in this) {
            when(last) {
                is Map<*, *> -> {
                    index = v
                    last = last[v]
                }

                is List<*> -> {
                    try {
                        index = v.toInt()
                        last = last[index]
                    } catch(e: Exception) {
                        throw ParseException(e)
                    }
                }

                is Array<*> -> {
                    try {
                        index = v.toInt()
                        last = last[index]
                    } catch(e: Exception) {
                        throw ParseException(e)
                    }
                }

                else -> {
                    throw ParseException("Cannot parse index $v.")
                }
            }
        }

        index to last
    }
}

fun String.toDoubleOrThrow() = this.toDoubleOrNull() ?: throw ParseException("Cannot convert $this to number.")

fun ConfigSection.parseToObject(accessible: Boolean = false): Any {
    if (tag?.clazz == null) throw IllegalArgumentException("Cannot parse section $name to object. Because couldn't find tag class.")
    val fieldMap = tag.clazz.allFieldMap()
    val obj = tag.clazz.getConstructor().apply {
        if (accessible) isAccessible = true
    }.newInstance()
    for (field in fieldMap.values) {
        if (accessible) {
            field.isAccessible = true
        }
        this[field.name]?.let {
            field.set(obj, it.value)
        }
    }

    return obj
}

fun ConfigSection.parseToMap(): Map<String, *> {
    return this.mutableMap.mapValues { it.value.value }
}

fun Any.asSection(
    name: String,
    parent: ConfigSection? = null,
    isProvGet: Boolean = false,
    isFinal: Boolean = false,
    tree: ConfigTree = emptyTree(),
    tag: ConfigTag? = null,
    accessible: Boolean = false
): ConfigSection {
    if(tree[name] != null) throw RuntimeException("The tree cannot have a section with the same name.")

    val fieldList = this::class.java.allFieldMap().values
    val section = ConfigSection(
        name,
        "",
        0,
        parent = tree,
        isFinal = isFinal,
        tag = tag
    )

    section.extendsSection = parent
    section.extendsSection?.children?.add(section)

    tag?.children?.add(section)
    tree[name] = section

    for(field in fieldList) {
        if(accessible) field.isAccessible = true
        section[field.name] = ConfigValue(
            field.name,
            if(isProvGet) Prov { field.get(this) } else field.get(this),
            "",
            0,
            section,
            isFinal
        )
    }

    if(parent != null) section.extends()
    return section
}

fun Map<String, *>.mapAsSection(
    name: String,
    parent: ConfigSection? = null,
    isFinal: Boolean = false,
    tree: ConfigTree = emptyTree(),
    tag: ConfigTag? = null
): ConfigSection {
    if(tree[name] != null) throw RuntimeException("The tree cannot have a section with the same name.")
    val section = ConfigSection(
        name,
        "",
        0,
        parent = tree,
        isFinal = isFinal,
        tag = tag
    )

    section.extendsSection = parent
    section.extendsSection?.children?.add(section)

    tag?.children?.add(section)
    tree[name] = section

    this.forEach { (k, v) ->
        section[k] = ConfigValue(
            k,
            v,
            "",
            0,
            section,
            isFinal
        )
    }

    if(parent != null) section.extends()
    return section
}

fun ConfigTree.getTags(tag: ConfigTag): List<ConfigSection> {
    return this.mutableMap.values.filter { it.tag == tag }
}

fun ConfigSection.extends() {
    var superSection = this.extendsSection!!.apply { println("base: $name") }
    val cache = mutableListOf<ConfigSection>()
    while(superSection.extendsSection != null && !superSection.isExtended) {
        if(superSection in cache) {
            superSection.setStackTrace()
            throw ParseException("Cyclic inheritance.")
        }
        superSection = superSection.extendsSection!!
        cache.add(superSection)
    }
    this.setStackTrace()
    if(superSection.isFinal) throw ParseException("Cannot extends a final section.")

    fun ConfigSection.forEachChildren(): Unit = this.children.forEach loop@{ child: ConfigSection ->
        if(child.isExtended) return
        this.mutableMap.forEach { (key, value) ->
            if(child[key] != null && value.isFinal) throw ParseException("Cannot override a final value.")
            if(child[key] == null) child[key] = value
        }
        child.isExtended = true
        if(child.children.isNotEmpty()) child.forEachChildren()
    }

    superSection.forEachChildren()
}

fun ConfigSection.writeToString(): String {
    val joiner = StringJoiner("\n")
    with(joiner) {
        val name = buildString {
            if(tag?.isSingle == true) {
                append("[:${this@writeToString.name}]")
                return@buildString
            }
            append("[")
            if(isFinal) {
                append("final ")
            }

            tag?.let {
                append(it.name)
                append(": ")
            }

            append(this@writeToString.name)
            extendsSection?.let { append(" extends ${it.name}") }
            append("]")
        }

        add(name)
        val fieldList = tag?.clazz?.allFieldMap()?.values
        for(value in mutableMap.values) {
            fieldList?.let {
                for(field in fieldList) {
                    if (field.name == value.name) {
                        val annotation = field.getDeclaredAnnotation(Comment::class.java)
                        annotation?.let { add("# ${annotation.msg.trimIndent()}") }
                    }
                }
            }

            add("${if(value.isFinal) "final " else ""}${value.name}: ${value.stringValue}")
        }
    }

    return joiner.toString()
}

fun ConfigSection.writeToFile(file: File) {
    file.writeText(this.writeToString())
}

fun ConfigTree.writeToString(): String {
    return buildString {
        for(section in mutableMap.values) {
            append("${section.writeToString()}\n")
        }
    }
}

fun ConfigTree.writeToFile(file: File) {
    file.writeText(this.writeToString())
}