package io.github.eedaegon.liveupdate

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveUpdateProtocolTest {
    @Test
    fun `dispatches every stable driver command`() {
        val adapter = FakeAdapter()

        assertEquals(true, ok(LiveUpdateProtocol.dispatch(adapter, "capabilities", "{}"))
            .getJSONObject("data").getBoolean("supported"))
        assertEquals(1, ok(LiveUpdateProtocol.dispatch(adapter, "read", "{}"))
            .getJSONArray("data").length())
        ok(LiveUpdateProtocol.dispatch(adapter, "write", "{\"records\":[]}"))
        assertEquals(1, ok(LiveUpdateProtocol.dispatch(adapter, "observe", "{}"))
            .getJSONArray("data").length())
        ok(LiveUpdateProtocol.dispatch(adapter, "post", "{\"record\":{\"id\":\"one\"}}"))
        ok(LiveUpdateProtocol.dispatch(adapter, "cancel", "{\"id\":\"one\"}"))

        assertEquals(listOf("write:0", "post:one", "cancel:one"), adapter.mutations)
    }

    @Test
    fun `rejects unknown commands fields and invalid identifiers`() {
        val adapter = FakeAdapter()
        for ((command, payload) in listOf(
            "unknown" to "{}",
            "read" to "{\"extra\":true}",
            "write" to "{\"records\":[],\"extra\":true}",
            "post" to "{\"record\":{}}",
            "cancel" to "{\"id\":\" \"}",
        )) {
            val error = JSONObject(LiveUpdateProtocol.dispatch(adapter, command, payload))
            assertEquals("error", error.getString("status"))
            assertEquals("INVALID_INPUT", error.getJSONObject("error").getString("code"))
        }
        assertFalse(adapter.mutations.isNotEmpty())
    }

    @Test
    fun `sanitizes unexpected adapter and parser failures`() {
        val adapter = FakeAdapter(fail = true)
        val expected = "The Android live update adapter failed."

        for ((command, payload) in listOf(
            "read" to "{}",
            "read" to "not-json",
        )) {
            val error = JSONObject(LiveUpdateProtocol.dispatch(adapter, command, payload))
                .getJSONObject("error")
            assertEquals("NATIVE_ERROR", error.getString("code"))
            assertEquals(expected, error.getString("message"))
        }
    }

    @Test
    fun `preserves stable adapter errors`() {
        val adapter = object : FakeAdapter() {
            override fun post(record: JSONObject) {
                throw AdapterFailure("PERMISSION_DENIED", "Notifications are disabled.")
            }
        }

        val response = JSONObject(LiveUpdateProtocol.dispatch(
            adapter,
            "post",
            "{\"record\":{\"id\":\"one\"}}",
        )).getJSONObject("error")

        assertEquals("PERMISSION_DENIED", response.getString("code"))
        assertEquals("Notifications are disabled.", response.getString("message"))
    }

    private fun ok(raw: String): JSONObject {
        val response = JSONObject(raw)
        assertEquals("ok", response.getString("status"))
        return response
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
}
