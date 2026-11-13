package io.github.eedaegon.liveupdate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFERENCES_NAME = "io.github.1eedaegon.liveupdate"
private const val EVENTS_KEY = "events.v2"
private const val EVENT_SEQUENCE_KEY = "eventSequence.v2"
private const val EVENT_DROPPED_KEY = "eventDropped.v2"
private const val MAX_EVENTS = 128
private const val MAX_EVENT_BYTES = 1_048_576
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private const val MAX_TIMESTAMP = 8_640_000_000_000_000L
private const val INVALID_MESSAGE = "Invalid live update request."
private const val NATIVE_MESSAGE = "The Android live update adapter failed."

/** Durable, process-safe queue for notification action and dismissal events. */
internal class EventStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun appendAction(activityId: String, actionId: String) {
        if (!validId(activityId) || !validActionId(actionId)) invalid()
        append(activityId, JSONObject().put("kind", "action").put("actionId", actionId))
    }

    fun appendDismissed(activityId: String) {
        if (!validId(activityId)) invalid()
        append(activityId, JSONObject().put("kind", "dismissed"))
    }

    fun appendOpened(activityId: String, actionId: String?) {
        if (!validId(activityId) || (actionId != null && !validActionId(actionId))) invalid()
        val kind = JSONObject().put("kind", "opened")
        if (actionId != null) kind.put("actionId", actionId)
        append(activityId, kind)
    }

    fun size(): Int = synchronized(EVENT_LOCK) { readEvents().length() }

    fun drain(): JSONObject = synchronized(EVENT_LOCK) {
        val events = readEvents()
        val dropped = readDropped()
        if (!preferences.edit().remove(EVENTS_KEY).putLong(EVENT_DROPPED_KEY, 0).commit()) nativeFailure()
        JSONObject().put("events", events).put("dropped", dropped)
    }

    private fun append(activityId: String, kind: JSONObject) = synchronized(EVENT_LOCK) {
        val previousSequence = preferences.getLong(EVENT_SEQUENCE_KEY, 0)
        if (previousSequence !in 0 until MAX_SAFE_INTEGER) nativeFailure()
        val sequence = previousSequence + 1
        val previous = readEvents()
        val dropped = readDropped()
        val overflow = previous.length() == MAX_EVENTS
        if (overflow && dropped == MAX_SAFE_INTEGER) nativeFailure()
        val events = JSONArray()
        for (index in (if (overflow) 1 else 0) until previous.length()) {
            events.put(previous.getJSONObject(index))
        }
        events.put(JSONObject()
            .put("sequence", sequence)
            .put("activityId", activityId)
            .put("occurredAt", System.currentTimeMillis())
            .put("kind", kind))
        if (!preferences.edit()
                .putString(EVENTS_KEY, events.toString())
                .putLong(EVENT_SEQUENCE_KEY, sequence)
                .putLong(EVENT_DROPPED_KEY, dropped + if (overflow) 1 else 0)
                .commit()
        ) nativeFailure()
    }

    private fun readEvents(): JSONArray {
        val raw = preferences.getString(EVENTS_KEY, "[]") ?: "[]"
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_EVENT_BYTES) nativeFailure()
        val events = try {
            JSONArray(raw)
        } catch (_: Exception) {
            nativeFailure()
        }
        if (events.length() > MAX_EVENTS) nativeFailure()
        validateEvents(events)
        return events
    }

    private fun validateEvents(events: JSONArray) {
        var previous = -1L
        for (index in 0 until events.length()) {
            val event = events.opt(index) as? JSONObject ?: nativeFailure()
            if (event.keys().asSequence().toSet() != setOf("sequence", "activityId", "occurredAt", "kind")) nativeFailure()
            val sequence = strictLong(event, "sequence", 0, MAX_SAFE_INTEGER)
            if (sequence <= previous || !validId(event.opt("activityId") as? String ?: nativeFailure())) nativeFailure()
            strictLong(event, "occurredAt", 0, MAX_TIMESTAMP)
            validateKind(event.opt("kind") as? JSONObject ?: nativeFailure())
            previous = sequence
        }
    }

    private fun validateKind(kind: JSONObject) {
        when (kind.opt("kind")) {
            "dismissed" -> if (kind.keys().asSequence().toSet() != setOf("kind")) nativeFailure()
            "action" -> {
                if (kind.keys().asSequence().toSet() != setOf("kind", "actionId")) nativeFailure()
                if (!validActionId(kind.opt("actionId") as? String ?: nativeFailure())) nativeFailure()
            }
            "opened" -> {
                if (kind.keys().asSequence().any { it != "kind" && it != "actionId" }) nativeFailure()
                if (kind.has("actionId") && !validActionId(kind.opt("actionId") as? String ?: nativeFailure())) {
                    nativeFailure()
                }
            }
            else -> nativeFailure()
        }
    }

    private fun strictLong(value: JSONObject, field: String, minimum: Long, maximum: Long): Long {
        val number = value.opt(field) as? Number ?: nativeFailure()
        val long = number.toLong()
        if (number.toDouble() != long.toDouble() || long !in minimum..maximum) nativeFailure()
        return long
    }

    private fun readDropped(): Long = preferences.getLong(EVENT_DROPPED_KEY, 0)
        .also { if (it !in 0..MAX_SAFE_INTEGER) nativeFailure() }

    private fun validId(value: String) = isValidContractText(value, 256)

    private fun validActionId(value: String): Boolean = value.length in 1..64 &&
        value.first().isAsciiLetterOrDigit() &&
        value.all { it.isAsciiLetterOrDigit() || it in "._:-" }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

    private fun invalid(): Nothing = throw AdapterFailure("INVALID_INPUT", INVALID_MESSAGE)

    private fun nativeFailure(): Nothing = throw AdapterFailure("NATIVE_ERROR", NATIVE_MESSAGE)

    private companion object {
        val EVENT_LOCK = Any()
    }
}
