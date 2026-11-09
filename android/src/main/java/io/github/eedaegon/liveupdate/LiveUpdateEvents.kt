package io.github.eedaegon.liveupdate

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

    internal fun stored() {
        try {
            listener?.onEventStored()
        } catch (_: Exception) {
            Log.e(LOG_TAG, "The live update event listener failed.")
        }
    }

    private const val LOG_TAG = "LiveUpdate"
}
