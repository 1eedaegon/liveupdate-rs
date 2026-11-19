package io.github.eedaegon.liveupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractValidationTest {
    // LU-COMPAT-6
    @Test
    fun aTextIsMeasuredInUtf8WithoutBeingEncoded() {
        val texts = listOf(
            "",
            "plain ascii",
            "café üß",
            "한글 라이브",
            "😀🚀",
            "aé한😀",
            // Half a pair, which the encoder writes as one `?`: at the end, at the start, and two in a row.
            "half \ud83d",
            "\ude00 half",
            "\ud83d😀",
        )
        for (text in texts) {
            val bytes = text.toByteArray(Charsets.UTF_8).size
            for (maximum in listOf(0, bytes - 1, bytes, bytes + 1, bytes * 3)) {
                if (maximum < 0) continue
                assertEquals("$text within $maximum", bytes > maximum, exceedsUtf8(text, maximum))
            }
        }
    }

    // LU-COMPAT-6
    @Test
    fun aLongTextIsMeasuredAtTheBoundOfTheBridge() {
        val bound = 1_048_576
        // Decided by the length alone: more chars than bytes allowed, and too few chars to reach them.
        assertTrue(exceedsUtf8("x".repeat(bound + 1), bound))
        assertFalse(exceedsUtf8("한".repeat(bound / 3), bound))
        // Counted: the same number of chars is within the bound or over it by what they are.
        assertFalse(exceedsUtf8("x".repeat(bound), bound))
        assertTrue(exceedsUtf8("x".repeat(bound - 1) + "é", bound))
        assertFalse(exceedsUtf8("한".repeat(bound / 3) + "x", bound))
        assertTrue(exceedsUtf8("한".repeat(bound / 3) + "xx", bound))
        assertFalse(exceedsUtf8("😀".repeat(bound / 4), bound))
        assertTrue(exceedsUtf8("😀".repeat(bound / 4) + "x", bound))
    }
}
