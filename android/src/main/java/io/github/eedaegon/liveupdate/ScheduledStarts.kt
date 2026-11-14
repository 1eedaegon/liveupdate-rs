package io.github.eedaegon.liveupdate

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal const val LIVE_UPDATE_START = "io.github.1eedaegon.liveupdate.START"

private const val STARTS_KEY = "starts"
private const val POSTED_AT = "postedAt"
private const val FAILED = "failed"
private const val EXACT_ALARM_API = 31
/** The window of the inexact alarm: Android allows none shorter from API 31. */
private const val ALARM_WINDOW = 600_000L
private const val NATIVE_MESSAGE = "The Android live update adapter failed."
private const val LOG_TAG = "LiveUpdate"

/** The note and the alarm are one state of the process: the alarm, a lifecycle call and a cancel take turns. */
private val LOCK = Any()

/**
 * The scheduled starts: receipts that the lifecycle stored with the state `scheduled`, and that the adapter
 * begins at their time, also while the application does not run. The receipts stay the lifecycle's and are only
 * read here. What the adapter did with one is noted under `starts`, for as long as its receipt is scheduled.
 *
 * The lifecycle stores a receipt unconfirmed (`postFailed`), schedules it, asks for the starts and only then
 * confirms it. So the alarm is set for an unconfirmed receipt as for any other, and only its post waits: one
 * whose time has come is posted when it is confirmed, or by the scheduling that names it.
 *
 * A start is posted before it is noted: a process that dies in between posts it once more.
 */
internal class ScheduledStarts(
    private val application: Context,
    private val adapter: AndroidAdapter,
    private val preferences: SharedPreferences,
    private val exactAlarms: Boolean?,
    private val listingGrace: Long,
) {
    private val alarms = application.getSystemService(AlarmManager::class.java)

    /** Whether an exact alarm may be set now. Below API 31 it needs no permission. */
    fun exact(): Boolean = exactAlarms ?: (Build.VERSION.SDK_INT < EXACT_ALARM_API || alarms.canScheduleExactAlarms())

    /** Begins what is due, forgets what is no longer scheduled, and arms the alarm for the next. */
    fun run() = synchronized(LOCK) { sweep(begin = true) }

    /**
     * Takes the stored receipt of [id], which the lifecycle is scheduling: an earlier note of it is forgotten,
     * and it is begun at once when its time has passed.
     */
    fun take(id: String) = synchronized(LOCK) { sweep(begin = true, taking = id) }

    /**
     * Runs [cancel], then forgets what is no longer scheduled and arms the alarm for the next, in one turn: a
     * start that is due does not post after its cancel. An alarm that cannot be set again fails no cancel.
     */
    fun rearm(cancel: () -> Unit) = synchronized(LOCK) {
        cancel()
        try {
            sweep(begin = false)
        } catch (_: Exception) {
            Log.e(LOG_TAG, "Could not set the alarm of the scheduled live updates again.")
        }
    }

    /** What became of the scheduled starts that Android does not list, after beginning what is due. */
    fun report(): JSONArray = synchronized(LOCK) {
        val notes = sweep(begin = true)
        val now = System.currentTimeMillis()
        val reports = JSONArray()
        for (id in notes.keys()) {
            val note = notes.getJSONObject(id)
            val outcome = when {
                note.optBoolean(FAILED) -> "gone"
                adapter.lists(id) -> continue
                // A clock that was set back does not keep a post awaited.
                now - note.optLong(POSTED_AT) in 0 until listingGrace -> "posted"
                else -> "gone"
            }
            reports.put(JSONObject().put("id", id).put("outcome", outcome))
        }
        reports
    }

    /** The notes of the receipts that are scheduled now, with those of what [begin] began. */
    private fun sweep(begin: Boolean, taking: String? = null): JSONObject {
        val before = preferences.getString(STARTS_KEY, null)
        val notes = read(before)
        val now = System.currentTimeMillis()
        val kept = JSONObject()
        var next: Long? = null
        for (record in scheduled()) {
            val id = record.getString("id")
            val at = record.getJSONObject("origin").getLong("startAt")
            val confirmed = id == taking || !record.optBoolean("postFailed")
            val noted = if (id == taking) null else notes.optJSONObject(id)
            val note = noted ?: if (begin && confirmed && at <= now) post(record, now) else null
            when {
                note != null -> kept.put(id, note)
                // One that is due and unconfirmed waits for its start again, not for an alarm.
                confirmed || at > now -> next = minOf(next ?: at, at)
            }
        }
        // The alarm first: a note that cannot be stored costs a second post, not the next start.
        arm(next)
        val after = kept.toString()
        if (after != (before ?: "{}") && !preferences.edit().putString(STARTS_KEY, after).commit()) {
            throw AdapterFailure("NATIVE_ERROR", NATIVE_MESSAGE)
        }
        return kept
    }

    /** The stored receipts that wait for their time or that the adapter began. */
    private fun scheduled(): List<JSONObject> {
        val records = adapter.read()
        return (0 until records.length()).mapNotNull(records::optJSONObject).filter { record ->
            record.optionalString("state") == "scheduled" && record.optionalString("id") != null &&
                record.optJSONObject("origin")?.opt("startAt") is Number
        }
    }

    /**
     * Posts [record] as its time has come, and answers the note of it. A post that fails is not tried again:
     * notifications are off by now, or the receipt is one the adapter refuses by now.
     */
    private fun post(record: JSONObject, now: Long): JSONObject = try {
        adapter.post(adapter.posted(record))
        JSONObject().put(POSTED_AT, now)
    } catch (_: Exception) {
        Log.e(LOG_TAG, "Could not post a scheduled live update at its time.")
        JSONObject().put(FAILED, true)
    }

    /** The notes are the adapter's own account: one that cannot be read is begun anew, not a failure. */
    private fun read(raw: String?): JSONObject = try {
        JSONObject(raw ?: "{}")
    } catch (_: JSONException) {
        JSONObject()
    }

    /**
     * One alarm for [at], the earliest start that waits, or none. Exact where the application may set one.
     * Otherwise two inexact ones: within a window while the device is awake, and one that also fires while it
     * dozes. Whichever comes first begins the activity, and the other finds its note.
     */
    private fun arm(at: Long?) {
        val first = alarm(0)
        val second = alarm(1)
        if (at == null) {
            alarms.cancel(first)
            alarms.cancel(second)
            return
        }
        // An alarm that is set replaces the one of its intent.
        if (exact()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, first)
                alarms.cancel(second)
                return
            } catch (_: SecurityException) {
                // The permission went between the question and the call.
            }
        }
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, ALARM_WINDOW, first)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, second)
    }

    private fun alarm(slot: Int): PendingIntent = PendingIntent.getBroadcast(
        application,
        slot,
        Intent(application, LiveUpdateStartReceiver::class.java).setAction(LIVE_UPDATE_START),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
