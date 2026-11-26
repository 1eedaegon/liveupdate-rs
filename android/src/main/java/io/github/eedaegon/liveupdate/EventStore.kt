package io.github.eedaegon.liveupdate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFERENCES_NAME = "io.github.1eedaegon.liveupdate"
private const val EVENTS_KEY = "events.v2"
private const val EVENT_SEQUENCE_KEY = "eventSequence.v2"
private const val EVENT_DROPPED_KEY = "eventDropped.v2"
private const val MAX_EVENTS = 128
private val PUSH_COMMANDS = setOf("start", "update", "end")
private const val MAX_EVENT_BYTES = 1_048_576
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private const val MAX_TIMESTAMP = 8_640_000_000_000_000L
private const val INVALID_MESSAGE = "Invalid live update request."
private const val NATIVE_MESSAGE = "The Android live update adapter failed."

/**
 * Durable, process-safe queue for notification action and dismissal events, and what a push did. A stored event keeps what it has:
 * a later version of the AAR may have stored a kind or a member that this one does not know, and the crate reads
 * such an event as one of an unknown kind.
 */
internal class EventStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    /** Each append answers the event it stored. */
    fun appendAction(activityId: String, actionId: String): JSONObject {
        if (!validId(activityId) || !validActionId(actionId)) invalid()
        return append(activityId, JSONObject().put("kind", "action").put("actionId", actionId))
    }

    fun appendDismissed(activityId: String): JSONObject {
        if (!validId(activityId)) invalid()
        return append(activityId, JSONObject().put("kind", "dismissed"))
    }

    fun appendOpened(activityId: String, actionId: String?): JSONObject {
        if (!validId(activityId) || (actionId != null && !validActionId(actionId))) invalid()
        val kind = JSONObject().put("kind", "opened")
        if (actionId != null) kind.put("actionId", actionId)
        return append(activityId, kind)
    }

    /**
     * A tap that the application's own component took. It is written without waiting for the disk: the application
     * acted on it already, and the event only tells Rust and the page.
     */
    fun appendHandled(activityId: String, actionId: String): JSONObject {
        if (!validId(activityId) || !validActionId(actionId)) invalid()
        return append(activityId, JSONObject().put("kind", "handled").put("actionId", actionId), durable = false)
    }

    /**
     * What a push did, from the crate's answer: its command, key and revision, with the activity's id when it did
     * what it asked, or the code of the refusal and the id empty when the push named no activity.
     */
    fun appendPushed(outcome: JSONObject): JSONObject {
        val command = outcome.optionalString("command")
        val key = outcome.optionalString("activityKey")
        val id = outcome.optionalString("id") ?: ""
        if (command !in PUSH_COMMANDS || key == null || (id.isNotEmpty() && !validId(id))) invalid()
        val kind = JSONObject().put("kind", "pushed").put("command", command).put("activityKey", key)
        if (outcome.has("revision")) kind.put("revision", outcome.getLong("revision"))
        outcome.optionalString("code")?.let { kind.put("code", it) }
        return append(id, kind)
    }

    fun size(): Int = synchronized(EVENT_LOCK) { readEvents().length() }

    fun drain(): JSONObject = synchronized(EVENT_LOCK) {
        val events = readEvents()
        val dropped = readDropped()
        if (!preferences.edit().remove(EVENTS_KEY).putLong(EVENT_DROPPED_KEY, 0).commit()) nativeFailure()
        JSONObject().put("events", events).put("dropped", dropped)
    }

    private fun append(activityId: String, kind: JSONObject, durable: Boolean = true): JSONObject = synchronized(EVENT_LOCK) {
        val previousSequence = preferences.getLong(EVENT_SEQUENCE_KEY, 0)
        if (previousSequence !in 0 until MAX_SAFE_INTEGER) nativeFailure()
        val sequence = previousSequence + 1
        val previous = readEvents()
        val event = JSONObject()
            .put("sequence", sequence)
            .put("activityId", activityId)
            .put("occurredAt", System.currentTimeMillis())
            .put("kind", kind)
        // The oldest events go for the newest: one over the number, and as many as it takes for the queue to be
        // read again. This version's events are small, and what a later version stored may not be.
        var gone = if (previous.length() == MAX_EVENTS) 1 else 0
        var raw = queued(previous, gone, event)
        while (exceedsUtf8(raw, MAX_EVENT_BYTES) && gone < previous.length()) {
            gone += 1
            raw = queued(previous, gone, event)
        }
        val dropped = readDropped() + gone
        if (dropped > MAX_SAFE_INTEGER) nativeFailure()
        val edit = preferences.edit()
            .putString(EVENTS_KEY, raw)
            .putLong(EVENT_SEQUENCE_KEY, sequence)
            .putLong(EVENT_DROPPED_KEY, dropped)
        if (durable) {
            if (!edit.commit()) nativeFailure()
        } else {
            edit.apply()
        }
        event
    }

    /** The queue as it is stored: [previous] without its [gone] oldest events, and then [event]. */
    private fun queued(previous: JSONArray, gone: Int, event: JSONObject): String {
        val events = JSONArray()
        for (index in gone until previous.length()) events.put(previous.getJSONObject(index))
        return events.put(event).toString()
    }

    private fun readEvents(): JSONArray {
        val raw = preferences.getString(EVENTS_KEY, "[]") ?: "[]"
        if (exceedsUtf8(raw, MAX_EVENT_BYTES)) nativeFailure()
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
            val sequence = strictLong(event, "sequence", 0, MAX_SAFE_INTEGER)
            if (sequence <= previous || !validId(event.opt("activityId") as? String ?: nativeFailure())) nativeFailure()
            strictLong(event, "occurredAt", 0, MAX_TIMESTAMP)
            validateKind(event.opt("kind") as? JSONObject ?: nativeFailure())
            previous = sequence
        }
    }

    private fun validateKind(kind: JSONObject) {
        when (kind.opt("kind") as? String ?: nativeFailure()) {
            "action", "handled" -> if (!validActionId(kind.opt("actionId") as? String ?: nativeFailure())) nativeFailure()
            "opened" -> if (kind.has("actionId") && !validActionId(kind.opt("actionId") as? String ?: nativeFailure())) {
                nativeFailure()
            }
            // A dismissal says nothing more, and a kind of a later version is not this one's to check.
            else -> Unit
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

    private fun validActionId(value: String): Boolean = isValidActionId(value)


    private fun invalid(): Nothing = throw AdapterFailure("INVALID_INPUT", INVALID_MESSAGE)

    private fun nativeFailure(): Nothing = throw AdapterFailure("NATIVE_ERROR", NATIVE_MESSAGE)

    private companion object {
        val EVENT_LOCK = Any()
    }
}

/** An action's id: 1 to 64 ASCII letters, digits and `._:-`, beginning with a letter or a digit. */
internal fun isValidActionId(value: String): Boolean = value.length in 1..64 &&
    value.first().isAsciiLetterOrDigit() &&
    value.all { it.isAsciiLetterOrDigit() || it in "._:-" }

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
