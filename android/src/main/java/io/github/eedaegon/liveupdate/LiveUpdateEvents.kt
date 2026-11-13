package io.github.eedaegon.liveupdate

import android.content.Context
import android.content.Intent
import android.util.Log

/** Called once an action or a dismissal has been stored, so the app can drain the events at once. */
fun interface LiveUpdateEventListener {
    fun onEventStored()
}

/**
 * The app's one listener for stored events. The receivers call it on their own thread after each durable append; keep
 * it short and drain from another thread (`LiveUpdate::events()` in Rust). A listener that throws is logged and the
 * event stays queued.
 */
object LiveUpdateEvents {
    @Volatile
    private var listener: LiveUpdateEventListener? = null

    /** Sets the listener, replacing the previous one; `null` clears it. */
    @JvmStatic
    fun setListener(listener: LiveUpdateEventListener?) {
        this.listener = listener
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
            if (known) {
                EventStore(context).appendOpened(activityId, actionId)
                stored()
            }
            known
        }
    } catch (_: Exception) {
        Log.e(LOG_TAG, "Could not persist a live update opening.")
        false
    }

    internal fun stored() {
        try {
            listener?.onEventStored()
        } catch (_: Exception) {
            Log.e(LOG_TAG, "The live update event listener failed.")
        }
    }

    private const val LOG_TAG = "LiveUpdate"
}
