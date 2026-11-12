package io.github.gon.liveupdate

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NATIVE_MESSAGE = "The Android live update adapter failed."
private const val MEBIBYTE = 1_048_576

class LiveUpdateProtocolTest {
    // LU-BRIDGE-6
    @Test
    fun dispatchesEveryStableDriverCommand() {
        val adapter = FakeAdapter()

        assertEquals(true, ok(LiveUpdateProtocol.dispatch(adapter, "capabilities", "{}"))
            .getJSONObject("data").getBoolean("supported"))
        assertEquals(1, ok(LiveUpdateProtocol.dispatch(adapter, "read", "{}"))
            .getJSONArray("data").length())
        assertTrue(ok(LiveUpdateProtocol.dispatch(adapter, "write", "{\"records\":[]}"))
            .isNull("data"))
        assertEquals(1, ok(LiveUpdateProtocol.dispatch(adapter, "observe", "{}"))
            .getJSONArray("data").length())
        ok(LiveUpdateProtocol.dispatch(adapter, "post", "{\"record\":{\"id\":\"one\"}}"))
        ok(LiveUpdateProtocol.dispatch(adapter, "cancel", "{\"id\":\"one\"}"))
        assertEquals("dismissed", ok(LiveUpdateProtocol.dispatch(adapter, "events", "{}"))
            .getJSONObject("data").getJSONArray("events")
            .getJSONObject(0).getJSONObject("kind").getString("kind"))

        assertEquals(listOf("write:0", "post:one", "cancel:one"), adapter.mutations)
    }

    // LU-BRIDGE-6
    @Test
    fun rejectsUnknownCommandsFieldsAndInvalidIdentifiers() {
        val adapter = FakeAdapter()
        for ((command, payload) in listOf(
            "unknown" to "{}",
            "Read" to "{}",
            "read" to "{\"extra\":true}",
            "capabilities" to "{\"records\":[]}",
            "observe" to "{\"id\":\"one\"}",
            "events" to "{\"extra\":true}",
            "write" to "{}",
            "write" to "{\"records\":[],\"extra\":true}",
            "post" to "{\"record\":{}}",
            "post" to "{\"record\":{\"id\":7}}",
            "post" to "{\"record\":{\"id\":null}}",
            "post" to "{}",
            "cancel" to "{\"id\":\" \"}",
            "cancel" to "{\"id\":\"\"}",
            "cancel" to "{\"id\":\"${"x".repeat(257)}\"}",
            "cancel" to "{}",
        )) {
            assertError("INVALID_INPUT", LiveUpdateProtocol.dispatch(adapter, command, payload))
        }
        assertTrue(adapter.mutations.isEmpty())
        ok(LiveUpdateProtocol.dispatch(adapter, "cancel", "{\"id\":\"${"x".repeat(256)}\"}"))
    }

    // LU-VALID-10, LU-BRIDGE-6
    @Test
    fun activityIdentifierLimitsCountUnicodeCodePoints() {
        val adapter = FakeAdapter()
        val maximum = "😀".repeat(256)
        val tooLong = maximum + "😀"

        ok(LiveUpdateProtocol.dispatch(
            adapter,
            "cancel",
            JSONObject().put("id", maximum).toString(),
        ))
        assertError("INVALID_INPUT", LiveUpdateProtocol.dispatch(
            adapter,
            "cancel",
            JSONObject().put("id", tooLong).toString(),
        ))
        for (blank in listOf(" \u3000", "\ufeff")) {
            assertError("INVALID_INPUT", LiveUpdateProtocol.dispatch(
                adapter,
                "cancel",
                JSONObject().put("id", blank).toString(),
            ))
        }
        assertEquals(listOf("cancel:$maximum"), adapter.mutations)
    }

    // LU-BRIDGE-6, LU-LIMIT-3
    @Test
    fun boundsPayloadBytesAndRecordCounts() {
        val adapter = FakeAdapter()
        val records = JSONArray().apply { repeat(161) { put(JSONObject()) } }
        assertError("INVALID_INPUT", LiveUpdateProtocol.dispatch(
            adapter, "write", JSONObject().put("records", records).toString(),
        ))
        val full = JSONArray().apply { repeat(160) { put(JSONObject()) } }
        ok(LiveUpdateProtocol.dispatch(adapter, "write", JSONObject().put("records", full).toString()))

        // The bound counts UTF-8 bytes: a three-byte character is three bytes.
        val wrapper = "{\"id\":\"\"}".length
        val threeByte = "{\"id\":\"${"가".repeat((MEBIBYTE - wrapper) / 3 + 1)}\"}"
        assertTrue(threeByte.length < MEBIBYTE)
        assertError("INVALID_INPUT", LiveUpdateProtocol.dispatch(adapter, "cancel", threeByte))
        val padding = " ".repeat(MEBIBYTE - "{}".length)
        ok(LiveUpdateProtocol.dispatch(adapter, "read", "{$padding}"))
        assertError("INVALID_INPUT", LiveUpdateProtocol.dispatch(adapter, "read", "{ $padding}"))
        assertEquals(listOf("write:160"), adapter.mutations)
    }

    // LU-ERR-3
    @Test
    fun sanitizesUnexpectedAdapterAndParserFailures() {
        val adapter = FakeAdapter(fail = true)

        for ((command, payload) in listOf(
            "read" to "{}",
            "read" to "not-json",
            "read" to "[]",
            "write" to "{\"records\":{}}",
            "post" to "{\"record\":[]}",
            "cancel" to "{\"id\":[]}",
            "events" to "not-json",
        )) {
            val error = assertError("NATIVE_ERROR", LiveUpdateProtocol.dispatch(adapter, command, payload))
            assertEquals(NATIVE_MESSAGE, error.getString("message"))
        }
    }

    // LU-ERR-3
    @Test
    fun preservesStableAdapterErrors() {
        val adapter = object : FakeAdapter() {
            override fun post(record: JSONObject) {
                throw AdapterFailure("PERMISSION_DENIED", "Notifications are disabled.")
            }
        }

        val response = assertError("PERMISSION_DENIED", LiveUpdateProtocol.dispatch(
            adapter,
            "post",
            "{\"record\":{\"id\":\"one\"}}",
        ))

        assertEquals("Notifications are disabled.", response.getString("message"))
    }

    // LU-ERR-4
    @Test
    fun adapterFailuresOnlyTakeTheStableCodes() {
        for (code in listOf(
            "INVALID_INPUT", "CONFLICT", "NOT_FOUND", "STALE_REVISION", "PERMISSION_DENIED",
            "UNSUPPORTED_FEATURE", "SYSTEM_LIMIT", "NATIVE_ERROR",
        )) {
            assertEquals(code, AdapterFailure(code, "message").code)
        }
        for (code in listOf("TIMEOUT", "invalid_input", "")) {
            assertThrows(IllegalArgumentException::class.java) { AdapterFailure(code, "message") }
        }
    }

    // LU-BRIDGE-6
    @Test
    fun optionalStringsAreStringsOnly() {
        val json = JSONObject("{\"text\":\"a\",\"number\":1,\"nothing\":null,\"object\":{}}")
        assertEquals("a", json.optionalString("text"))
        assertNull(json.optionalString("number"))
        assertNull(json.optionalString("nothing"))
        assertNull(json.optionalString("object"))
        assertNull(json.optionalString("missing"))
    }

    // LU-BRIDGE-6
    @Test
    fun answersWithTheStableEnvelope() {
        val response = JSONObject(LiveUpdateProtocol.dispatch(FakeAdapter(), "cancel", "{\"id\":\"one\"}"))
        assertEquals(setOf("status", "data"), response.keys().asSequence().toSet())
        val error = JSONObject(LiveUpdateProtocol.dispatch(FakeAdapter(), "unknown", "{}"))
        assertEquals(setOf("status", "error"), error.keys().asSequence().toSet())
        assertEquals(
            setOf("code", "message"),
            error.getJSONObject("error").keys().asSequence().toSet(),
        )
    }

    private fun ok(raw: String): JSONObject {
        val response = JSONObject(raw)
        assertEquals(raw, "ok", response.getString("status"))
        return response
    }

    private fun assertError(code: String, raw: String): JSONObject {
        val response = JSONObject(raw)
        assertEquals(raw.take(200), "error", response.getString("status"))
        val error = response.getJSONObject("error")
        assertEquals(raw.take(200), code, error.getString("code"))
        return error
    }
}

private open class FakeAdapter(private val fail: Boolean = false) : ManagedAdapter {
    val mutations = mutableListOf<String>()

    override fun capabilities() = JSONObject()
        .put("supported", true)
        .put("notificationsEnabled", true)
        .put("promotionEnabled", true)

    override fun read(): JSONArray {
        if (fail) error("sensitive platform cause")
        return JSONArray().put(JSONObject().put("id", "one"))
    }

    override fun write(records: JSONArray) {
        mutations += "write:${records.length()}"
    }

    override fun observe() = JSONArray().put(JSONObject().put("id", "one"))

    override fun post(record: JSONObject) {
        mutations += "post:${record.getString("id")}"
    }

    override fun cancel(id: String) {
        mutations += "cancel:$id"
    }

    override fun drainEvents() = JSONObject()
        .put("events", JSONArray().put(JSONObject()
            .put("sequence", 1)
            .put("activityId", "one")
            .put("occurredAt", 1_000)
            .put("kind", JSONObject().put("kind", "dismissed"))))
        .put("dropped", 0)
}
