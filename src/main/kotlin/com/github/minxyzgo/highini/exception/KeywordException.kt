package com.github.minxyzgo.highini.exception

import com.github.minxyzgo.highini.parse.*

class KeywordException(
    keyword: Keyword
) : SyntaxException("The keyword ${keyword.codeName()} cannot be used here.")