package com.github.minxyzgo.highini.parse

import java.util.*

enum class Keyword {
    Extends, Final, Export;

    fun codeName() = this.name.lowercase(Locale.getDefault())
}