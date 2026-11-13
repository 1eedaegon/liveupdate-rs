package io.github.eedaegon.liveupdate

import android.app.Notification
import android.app.Service
import android.os.Build
import android.util.Log
import java.lang.ref.WeakReference

/** Called once the activity a service held has ended, after the service left the foreground. */
fun interface LiveUpdateForegroundListener {
    fun onReleased(activityId: String)
}

/**
 * Lets a foreground service of the application hold the notification of the activity that started with the
 * foreground placement, so that Android shows one notification for both. The application owns the service, its
 * type and its permission; this library declares none.
 */
object LiveUpdateForeground {
    /** The id of the notification a service holds. It has no tag: `startForeground` takes none. */
    const val NOTIFICATION_ID: Int = 0x4C55_0001

    private const val LOG_TAG = "LiveUpdate"

    private class Held(val service: WeakReference<Service>, val activityId: String, val type: Int)

    /** Attach, detach and release run one at a time, so that an end never misses a service that is attaching. */
    private val lock = Any()
    private var held: Held? = null

    @Volatile
    private var listener: LiveUpdateForegroundListener? = null

    /**
     * Puts [service] in the foreground with the notification of the live activity that has the foreground
     * placement, built from its stored receipt, and answers whether it did. It never alerts. A service that
     * attaches after its process restarted, before the first lifecycle call, shows the activity again.
     * [foregroundServiceType] is the type the service declares; it is passed on from API 29.
     *
     * False means that no live activity has the placement or that its receipt cannot be read. A service that
     * Android started in the foreground must then show a notification of its own or stop. What `startForeground`
     * itself throws, as for a type the manifest does not declare, is the caller's to see.
     */
    @JvmStatic
    fun attach(service: Service, foregroundServiceType: Int): Boolean = synchronized(lock) {
        val (activityId, notification) = try {
            AndroidAdapter(service).foregroundNotification()
        } catch (_: Exception) {
            Log.e(LOG_TAG, "Could not read the live update a service attaches to.")
            null
        } ?: return false
        // One service holds the notification: another one that did lets go of it.
        held?.service?.get()?.takeIf { it !== service }?.stopForeground(Service.STOP_FOREGROUND_DETACH)
        show(service, notification, foregroundServiceType)
        held = Held(WeakReference(service), activityId, foregroundServiceType)
        true
    }

    /**
     * Takes [service] out of the foreground. The notification stays, as an ordinary one. A service calls this
     * before it stops for another reason than the activity's end. Android posts the notification of a service
     * that detaches once more, a moment later; an end in that same instant can lose against that post, until
     * the next lifecycle call reconciles.
     */
    @JvmStatic
    fun detach(service: Service) = synchronized(lock) {
        if (held?.service?.get() === service) held = null
        service.stopForeground(Service.STOP_FOREGROUND_DETACH)
    }

    /**
     * Sets the listener, replacing the previous one; `null` clears it. It runs on the thread that ended the
     * activity, after the service left the foreground.
     */
    @JvmStatic
    fun setListener(listener: LiveUpdateForegroundListener?) {
        this.listener = listener
    }

    /**
     * Shows [notification] as the newer content of [activityId] through the service that holds it, and answers
     * whether one does. When a service detaches, Android posts the notification that the service was last
     * given once more: a held activity is therefore updated through its service, never past it.
     */
    internal fun update(activityId: String, notification: Notification): Boolean = synchronized(lock) {
        val current = held?.takeIf { it.activityId == activityId } ?: return false
        val service = current.service.get() ?: return false
        show(service, notification, current.type)
        true
    }

    /**
     * The service that holds the notification of [activityId] leaves the foreground with it, because the
     * activity ended: Android ignores the cancel of a notification that a service holds.
     */
    internal fun release(activityId: String) {
        synchronized(lock) {
            val current = held?.takeIf { it.activityId == activityId } ?: return
            held = null
            current.service.get()?.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        }
        try {
            listener?.onReleased(activityId)
        } catch (_: Exception) {
            Log.e(LOG_TAG, "The live update foreground listener failed.")
        }
    }

    private fun show(service: Service, notification: Notification, type: Int) {
        if (Build.VERSION.SDK_INT >= 29) {
            service.startForeground(NOTIFICATION_ID, notification, type)
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }
}
