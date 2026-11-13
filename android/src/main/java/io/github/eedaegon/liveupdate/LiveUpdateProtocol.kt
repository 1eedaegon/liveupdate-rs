package io.github.eedaegon.liveupdate

import org.json.JSONArray
import org.json.JSONObject

private const val MAX_BRIDGE_BYTES = 1_048_576
private const val MAX_RECORDS = 160
private const val INVALID_MESSAGE = "Invalid live update request."
private const val NATIVE_MESSAGE = "The Android live update adapter failed."

internal class AdapterFailure(val code: String, message: String) : Exception(message) {
    init {
        require(code in STABLE_CODES)
    }

    private companion object {
        val STABLE_CODES = setOf(
            "INVALID_INPUT",
            "CONFLICT",
            "NOT_FOUND",
            "STALE_REVISION",
            "PERMISSION_DENIED",
            "UNSUPPORTED_FEATURE",
            "SYSTEM_LIMIT",
            "NATIVE_ERROR",
        )
    }
}

internal interface ManagedAdapter {
    fun capabilities(): JSONObject
    fun read(): JSONArray
    fun write(records: JSONArray)
    fun observe(): JSONArray
    fun post(record: JSONObject)
    fun cancel(id: String)
    fun drainEvents(): JSONObject
}

internal object LiveUpdateProtocol {
    fun dispatch(adapter: ManagedAdapter, command: String, rawPayload: String): String = try {
        if (rawPayload.toByteArray(Charsets.UTF_8).size > MAX_BRIDGE_BYTES) invalid()
        val payload = JSONObject(rawPayload)
        val data = when (command) {
            "capabilities" -> emptyPayload(payload) { adapter.capabilities() }
            "read" -> emptyPayload(payload) { adapter.read() }
            "write" -> write(adapter, payload)
            "observe" -> emptyPayload(payload) { adapter.observe() }
            "post" -> post(adapter, payload)
            "cancel" -> cancel(adapter, payload)
            "events" -> emptyPayload(payload) { adapter.drainEvents() }
            else -> invalid()
        }
        success(data).toString()
    } catch (failure: AdapterFailure) {
        error(failure.code, failure.message ?: NATIVE_MESSAGE).toString()
    } catch (_: Exception) {
        error("NATIVE_ERROR", NATIVE_MESSAGE).toString()
    } catch (_: LinkageError) {
        // A platform method or class that this device lacks: an answer, not a throw into the host.
        error("NATIVE_ERROR", NATIVE_MESSAGE).toString()
    }

    private fun <T> emptyPayload(payload: JSONObject, action: () -> T): T {
        requireFields(payload, emptySet())
        return action()
    }

    private fun write(adapter: ManagedAdapter, payload: JSONObject): Any {
        requireFields(payload, setOf("records"))
        val records = payload.getJSONArray("records")
        if (records.length() > MAX_RECORDS) invalid()
        adapter.write(records)
        return JSONObject.NULL
    }

    private fun post(adapter: ManagedAdapter, payload: JSONObject): Any {
        requireFields(payload, setOf("record"))
        val record = payload.getJSONObject("record")
        validId(record.optionalString("id"))
        adapter.post(record)
        return JSONObject.NULL
    }

    private fun cancel(adapter: ManagedAdapter, payload: JSONObject): Any {
        requireFields(payload, setOf("id"))
        val id = payload.getString("id")
        validId(id)
        adapter.cancel(id)
        return JSONObject.NULL
    }

    private fun requireFields(payload: JSONObject, expected: Set<String>) {
        if (payload.keys().asSequence().toSet() != expected) invalid()
    }

    private fun validId(id: String?) {
        if (id == null || !isValidContractText(id, 256)) invalid()
    }

    private fun success(data: Any) = JSONObject().put("status", "ok").put("data", data)

    private fun error(code: String, message: String) = JSONObject()
        .put("status", "error")
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun invalid(): Nothing = throw AdapterFailure("INVALID_INPUT", INVALID_MESSAGE)
}

internal fun JSONObject.optionalString(name: String): String? =
    if (!has(name) || isNull(name)) null else opt(name) as? String
