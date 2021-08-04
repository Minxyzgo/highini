package com.github.minxyzgo.highini.util

import com.github.minxyzgo.highini.exception.*
import java.lang.reflect.*

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
