package com.github.minxyzgo.highini.parse

object Token {
    val allSection = Regex("""^\[.*]""")
    val map = Regex("""^\{.*}""")
    val section = Regex("""(?<=\[)[\d\w]+(?=])""")
//    val key = Regex("""\w*(?=:)""")
//    val value = Regex("""(?<=:)\w*""")
//
//    val keyValue = { str: String ->
//        val keyStr = key.find(str)?.value
//        val valueStr = value.find(str)?.value
//        keyStr to valueStr
//    }
    val number = Regex("""^\d+$""")
    val numberRange = Regex("""^(\d+\.\.\d+)$""")
    val keyValue = Regex("""(\w*=|:\w*)+""")
}