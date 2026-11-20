package io.github.eedaegon.liveupdate

/**
 * The number of elements of the JSON array that [raw] is, counted by its brackets, commas and strings without
 * building them; null when [raw] is not one array whose brackets pair, with no element left out and only JSON's
 * whitespace around. What is inside the elements is not checked: whoever parses them does.
 */
internal fun topLevelArrayLength(raw: String): Int? {
    var index = 0
    while (index < raw.length && raw[index].isJsonWhitespace()) index += 1
    if (index == raw.length || raw[index] != '[') return null
    val closers = ArrayDeque<Char>()
    var inString = false
    var escaped = false
    var elements = 0
    // Whether the element at the array's own depth has begun since the last comma.
    var element = false
    while (index < raw.length) {
        val character = raw[index]
        index += 1
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            continue
        }
        when (character) {
            '"' -> {
                inString = true
                if (closers.size == 1) element = true
            }
            '[', '{' -> {
                if (closers.size == 1) element = true
                closers.addLast(if (character == '[') ']' else '}')
            }
            ']', '}' -> {
                if (closers.removeLastOrNull() != character) return null
                if (closers.isEmpty()) {
                    // The array ends: a comma before it left an element out, and only whitespace may follow.
                    if (element) elements += 1 else if (elements > 0) return null
                    while (index < raw.length) if (!raw[index++].isJsonWhitespace()) return null
                    return elements
                }
            }
            ',' -> if (closers.size == 1) {
                if (!element) return null
                elements += 1
                element = false
            }
            else -> if (closers.size == 1 && !character.isJsonWhitespace()) element = true
        }
    }
    return null
}

private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'
