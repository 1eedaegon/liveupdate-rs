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
