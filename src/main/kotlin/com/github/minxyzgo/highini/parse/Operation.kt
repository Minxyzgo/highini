package com.github.minxyzgo.highini.parse

import com.github.minxyzgo.highini.exception.*
import com.github.minxyzgo.highini.util.*

@Suppress("unused")
enum class Operation(
    val priority: Int,
    val code: String,
    val parser: (first: String, second: String) -> Boolean
) {
    and(2, "&&", { first, second ->
        first.toBoolean() && second.toBoolean()
    }),
    or(1, "||", { first, second ->
        first.toBoolean() || second.toBoolean()
    }),
    not(3, "!", { first, _ ->
        !first.toBoolean()
    }),
    notEquals(3, "!=", { first, second ->
        first != second
    }),
    equals(3, "==", { first, second ->
        first.equals(second)
    }),
    greaterOrEqual(3, ">=", { first, second ->
        first.toDoubleOrThrow() >= second.toDoubleOrThrow()
    }),
    lessThanOrEqual(3, "<=", { first, second ->
        first.toDoubleOrThrow() <= second.toDoubleOrThrow()
    }),
    moreThan(3, ">", { first, second ->
        first.toDoubleOrThrow() > second.toDoubleOrThrow()
    }),
    lessThan(3, "<", { first, second ->
        first.toDoubleOrThrow() < second.toDoubleOrThrow()
    }),
    inRange(3, "in", { first, second ->
        if(!second.contains("..")) throw SyntaxException("syntax error.")
        first.toDoubleOrThrow() in second.split("..").run { this[0].toDoubleOrThrow()..this[1].toDoubleOrThrow() }
    }),
    notInRange(3, "!in", { first, second ->
        if(!second.contains("..")) throw SyntaxException("syntax error.")
        first.toDoubleOrThrow() !in second.split("..").run { this[0].toDoubleOrThrow()..this[1].toDoubleOrThrow() }
    })
    ;
}