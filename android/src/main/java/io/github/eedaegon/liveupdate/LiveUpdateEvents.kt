package io.github.eedaegon.liveupdate

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Called once an action or a dismissal has been stored, so the app can drain the events at once. */
fun interface LiveUpdateEventListener {
    fun onEventStored()
}

/** Told of each event that is stored, with its content. */
fun interface LiveUpdateNativeListener {
    fun onEvent(event: LiveUpdateEvent)
}

/**
 * An event as it was stored. [kind] is `action`, `dismissed`, `opened`, `handled` or `pushed`, or a kind of a later
 * version; [actionId] names the action of an `action`, a `handled`, and an `opened` that an action opened.
 */
data class LiveUpdateEvent(
    val sequence: Long,
    val activityId: String,
    val occurredAt: Long,
    val kind: String,
    val actionId: String?,
) {
    internal companion object {
        fun of(event: JSONObject): LiveUpdateEvent {
            val kind = event.getJSONObject("kind")
            return LiveUpdateEvent(
                event.getLong("sequence"),
                event.getString("activityId"),
                event.getLong("occurredAt"),
                kind.getString("kind"),
                kind.optionalString("actionId"),
            )
        }
    }
}

/**
 * The app's one listener for stored events. The receivers call it on their own thread after each durable append; keep
 * it short and drain from another thread (`LiveUpdate::events()` in Rust). A listener that throws is logged and the
 * event stays queued.
 */
object LiveUpdateEvents {
    @Volatile
    private var listener: LiveUpdateEventListener? = null
    private val natives = CopyOnWriteArraySet<LiveUpdateNativeListener>()

    /** Sets the listener, replacing the previous one; `null` clears it. */
    @JvmStatic
    fun setListener(listener: LiveUpdateEventListener?) {
        this.listener = listener
    }

    /**
     * Adds a listener that is told of every stored event with its content, on the thread that stored it, after the
     * one listener of [setListener]. It takes nothing from the queue, which Rust drains as before: native code of
     * the application, such as a service, may watch the taps, dismissals and openings beside it. A listener that
     * throws is logged, and the others are told. Listeners live in memory: a process that a tap started has those
     * that the application registered as it started.
     */
    @JvmStatic
    fun addNativeListener(listener: LiveUpdateNativeListener) {
        natives.add(listener)
    }

    /** Removes a listener that [addNativeListener] added. */
    @JvmStatic
    fun removeNativeListener(listener: LiveUpdateNativeListener) {
        natives.remove(listener)
    }

    /**
     * Queues an `opened` event for an intent that a notification of this library opened the application with,
     * and answers whether it did. An activity passes the intent it was created or brought forward for, once: the
     * intent's marks are removed, so the same intent is not counted again. An intent that names no stored activity
     * is not counted either, and an intent that cannot be read is not counted and never throws. The call reads
     * and writes a small preferences file on the calling thread, as the receivers of this library do.
     */
    @JvmStatic
    fun opened(context: Context, intent: Intent?): Boolean = try {
        val activityId = intent?.getStringExtra(EXTRA_ACTIVITY_ID)
        if (intent == null || activityId == null) {
            false
        } else {
            val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
            intent.removeExtra(EXTRA_ACTIVITY_ID)
            intent.removeExtra(EXTRA_ACTION_ID)
            val known = AndroidAdapter(context).knows(activityId)
            if (known) stored(EventStore(context).appendOpened(activityId, actionId))
            known
        }
    } catch (_: Exception) {
        Log.e(LOG_TAG, "Could not persist a live update opening.")
        false
    }

    /** Tells the one listener, and the native listeners with [event]'s content. */
    internal fun stored(event: JSONObject) {
        try {
            listener?.onEventStored()
        } catch (_: Exception) {
            Log.e(LOG_TAG, "The live update event listener failed.")
        }
        if (natives.isEmpty()) return
        val content = try {
            LiveUpdateEvent.of(event)
        } catch (_: Exception) {
            Log.e(LOG_TAG, "A stored live update event could not be read for its listeners.")
            return
        }
        for (native in natives) {
            try {
                native.onEvent(content)
            } catch (_: Exception) {
                Log.e(LOG_TAG, "A live update native listener failed.")
            }
        }
    }

    private const val LOG_TAG = "LiveUpdate"
}
