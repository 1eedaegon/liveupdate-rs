package io.github.eedaegon.liveupdate

/** Matches Rust's Unicode-scalar text bound and its closed whitespace definition. */
internal fun isValidContractText(value: String, maximum: Int): Boolean {
    if (value.isEmpty()) return false
    var index = 0
    var count = 0
    var hasContent = false
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        count += 1
        if (count > maximum) return false
        if (!isContractWhitespace(codePoint)) hasContent = true
        index += Character.charCount(codePoint)
    }
    return hasContent
}

/**
 * Whether [text] takes more than [maximum] bytes as UTF-8, without encoding it. A char takes one to three bytes,
 * so most texts are decided by their length alone.
 */
internal fun exceedsUtf8(text: String, maximum: Int): Boolean {
    if (text.length > maximum) return true
    if (text.length.toLong() * 3 <= maximum) return false
    var bytes = 0L
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val pair = Character.isHighSurrogate(char) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1])
        bytes += when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            // A pair of surrogates is one code point of four bytes; half a pair is written as one `?`.
            pair -> 4
            Character.isSurrogate(char) -> 1
            else -> 3
        }
        if (bytes > maximum) return true
        index += if (pair) 2 else 1
    }
    return false
}

private fun isContractWhitespace(codePoint: Int): Boolean =
    codePoint in 0x0009..0x000d ||
        codePoint == 0x0020 ||
        codePoint == 0x00a0 ||
        codePoint == 0x1680 ||
        codePoint in 0x2000..0x200a ||
        codePoint == 0x2028 ||
        codePoint == 0x2029 ||
        codePoint == 0x202f ||
        codePoint == 0x205f ||
        codePoint == 0x3000 ||
        codePoint == 0xfeff

/** Matches Rust's `valid_key`: an ASCII letter or digit, then letters, digits, `.`, `_`, `:` and `-`, at most 128. */
internal fun isValidActivityKey(value: String): Boolean =
    value.length in 1..128 && value[0].isAsciiLetterOrDigit() &&
        value.all { it.isAsciiLetterOrDigit() || it in ".:_-" }

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
