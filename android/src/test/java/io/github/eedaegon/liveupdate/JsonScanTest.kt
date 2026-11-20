package io.github.eedaegon.liveupdate

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonScanTest {
    // LU-LIMIT-3
    @Test
    fun theElementsOfATopLevelArrayAreCountedWithoutParsingThem() {
        assertEquals(0, topLevelArrayLength("[]"))
        assertEquals(0, topLevelArrayLength(" [ \n ] "))
        assertEquals(1, topLevelArrayLength("[{}]"))
        assertEquals(3, topLevelArrayLength("[1, \"two\", {\"three\": [3, 3]}]"))
        assertEquals(2, topLevelArrayLength("[[1,2],[3]]"))
        // Brackets, commas and quotes inside strings are text.
        assertEquals(2, topLevelArrayLength("[{\"a\":\"],[\"},{\"b\":\"\\\",[\"}]"))
        assertEquals(1, topLevelArrayLength("[\"\\\\\"]"))
        // The receipts as the adapter stores them: as many as org.json reads.
        val stored = JSONArray().apply {
            repeat(160) { put(JSONObject().put("id", "id-$it").put("title", "a, [b] {c} \"d\" \\ e")) }
        }.toString()
        assertEquals(JSONArray(stored).length(), topLevelArrayLength(stored))

        assertEquals(1, topLevelArrayLength("[\"\\u0022\"]"))
        assertEquals(2, topLevelArrayLength("[ \"a\\\\\" , \"b\" ]"))
        val nested = "[{\"a\":[1,{\"b\":[]}]},[[],[[]]],\"x\",null,true,-1.5e3]"
        assertEquals(JSONArray(nested).length(), topLevelArrayLength(nested))

        // What is no array, or not one whole array, has no length.
        for (raw in listOf("", "{}", "[", "[1", "1]", "[1]]", "[1] x", "[\"]", "[}", "[1,{]", "null")) {
            assertNull(raw, topLevelArrayLength(raw))
        }
        // Brackets that do not pair, an element left out, and whitespace that JSON does not have.
        for (raw in listOf("[{]]", "[[}]", "[,]", "[1,]", "[1,,2]", "[,1]", "\u00a0[1]", "[1]\u2028")) {
            assertNull(raw, topLevelArrayLength(raw))
        }
    }
}
