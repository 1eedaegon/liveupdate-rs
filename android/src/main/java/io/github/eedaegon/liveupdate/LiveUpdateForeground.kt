package io.github.eedaegon.liveupdate

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.os.Build
import android.util.Log
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Called once the activity a service held has ended, after the service left the foreground or, when it attached with
 * a fallback, showed its own notification again.
 */
fun interface LiveUpdateForegroundListener {
    fun onReleased(activityId: String)
}

/**
 * Builds the application's own notification of a foreground service, at the time it is shown. It runs on the calling
 * thread outside the library's lock, and may ask the application's own state.
 */
fun interface LiveUpdateFallback {
    fun notification(): Notification
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

    /**
     * The application's own notification of a service: its id, its builder, the notification it last built, and
     * whether the service shows it now.
     */
    private class Fallback(
        val id: Int,
        val type: Int,
        val build: LiveUpdateFallback,
        var last: Notification,
        var shown: Boolean,
    )

    /** Attach, detach and release run one at a time, so that an end never misses a service that is attaching. */
    private val lock = Any()
    private var held: Held? = null

    /** The fallback of each service that attached with one; a service that is gone takes its entry along. */
    private val fallbacks = WeakHashMap<Service, Fallback>()

    @Volatile
    private var listener: LiveUpdateForegroundListener? = null

    /**
     * Puts [service] in the foreground with the notification of the live activity that has the foreground
     * placement, built from its stored receipt, and answers whether it did. It never alerts. A service that
     * attaches after its process restarted, before the first lifecycle call, shows the activity again.
     * [foregroundServiceType] is the type the service declares; it is passed on from API 29.
     *
     * False means that no live activity has the placement, that its receipt cannot be read, or that its clock is
     * older than Android posts. A service that Android started in the foreground must then show a notification of
     * its own or stop. What `startForeground` itself throws, as for a type the manifest does not declare, is the
     * caller's to see.
     */
    @JvmStatic
    fun attach(service: Service, foregroundServiceType: Int): Boolean = synchronized(lock) {
        attachHeld(service, foregroundServiceType)
    }

    /**
     * Puts [service] in the foreground at once, with the notification of the live activity that has the foreground
     * placement, as [attach] does, and answers true; or else with the application's own notification, which
     * [fallback] builds then, under [fallbackId], and answers false. A service of a type that must be in the
     * foreground within seconds of its start, such as one that records, calls this first in `onStartCommand`.
     *
     * The service is then never without a notification. A later [attach] or [attachOr] that finds an activity
     * cancels the fallback it replaces. When the activity ends, the service shows its fallback again and stays in
     * the foreground, and the listener is told; so does a service whose activity another service took. [detach]
     * forgets the fallback. [fallback] is called on each call of this, and again when the activity ends, outside
     * the library's lock. [fallbackId] may not be [NOTIFICATION_ID]. What [fallback] and `startForeground` throw
     * is the caller's to see.
     */
    @JvmStatic
    fun attachOr(service: Service, foregroundServiceType: Int, fallbackId: Int, fallback: LiveUpdateFallback): Boolean {
        require(fallbackId != NOTIFICATION_ID) { "The fallback's id is that of the activity's notification." }
        // Built before the lock: the application's builder may wait for the lifecycle, which may wait for the lock.
        val own = fallback.notification()
        return synchronized(lock) {
            val previous = fallbacks[service]
            if (previous != null && previous.shown && previous.id != fallbackId) {
                manager(service)?.cancel(previous.id)
            }
            val next = Fallback(fallbackId, foregroundServiceType, fallback, own, previous?.shown == true && previous.id == fallbackId)
            fallbacks[service] = next
            if (attachHeld(service, foregroundServiceType)) return@synchronized true
            showFallback(service, next, own)
            false
        }
    }

    private fun attachHeld(service: Service, type: Int): Boolean {
        val (activityId, notification) = try {
            AndroidAdapter(service).foregroundNotification()
        } catch (_: Exception) {
            Log.e(LOG_TAG, "Could not read the live update a service attaches to, or its clock is too old.")
            null
        } ?: return false
        // One service holds the notification: another one that did shows its own again, or lets go of it.
        held?.service?.get()?.takeIf { it !== service }?.let { other ->
            val own = fallbacks[other]
            if (own == null || !tryShowFallback(other, own, own.last)) other.stopForeground(Service.STOP_FOREGROUND_DETACH)
        }
        show(service, notification, type)
        held = Held(WeakReference(service), activityId, type)
        // The activity's notification is the service's now: the fallback it showed goes.
        fallbacks[service]?.takeIf { it.shown }?.let { replaced ->
            replaced.shown = false
            manager(service)?.cancel(replaced.id)
        }
        return true
    }

    private fun showFallback(service: Service, fallback: Fallback, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            service.startForeground(fallback.id, notification, fallback.type)
        } else {
            service.startForeground(fallback.id, notification)
        }
        fallback.last = notification
        fallback.shown = true
    }

    private fun tryShowFallback(service: Service, fallback: Fallback, notification: Notification): Boolean = try {
        showFallback(service, fallback, notification)
        true
    } catch (_: Exception) {
        Log.e(LOG_TAG, "The fallback of a live update's service could not be shown.")
        false
    }

    private fun manager(service: Service): NotificationManager? =
        service.getSystemService(NotificationManager::class.java)

    /**
     * Takes [service] out of the foreground. The notification stays, as an ordinary one. A service calls this
     * before it stops for another reason than the activity's end. Android posts the notification of a service
     * that detaches once more, a moment later; an end in that same instant can lose against that post, until
     * the next lifecycle call reconciles.
     */
    @JvmStatic
    fun detach(service: Service) = synchronized(lock) {
        if (held?.service?.get() === service) held = null
        fallbacks.remove(service)
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
     * activity ended: Android ignores the cancel of a notification that a service holds. A service that attached
     * with a fallback shows it instead, and stays in the foreground.
     */
    internal fun release(activityId: String) {
        val (service, own) = synchronized(lock) {
            val current = held?.takeIf { it.activityId == activityId } ?: return
            held = null
            val service = current.service.get()
            val own = service?.let { fallbacks[it] }
            if (service != null && own == null) service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            service to own
        }
        if (service != null && own != null) restore(service, own)
        try {
            listener?.onReleased(activityId)
        } catch (_: Exception) {
            Log.e(LOG_TAG, "The live update foreground listener failed.")
        }
    }

    /**
     * Shows the fallback of [service] again in place of the activity's notification, which the service held until the
     * activity ended. The builder runs without the lock; a later activity that the service took meanwhile stays.
     */
    private fun restore(service: Service, own: Fallback) {
        val notification = try {
            own.build.notification()
        } catch (_: Exception) {
            Log.e(LOG_TAG, "The fallback of a live update's service could not be built.")
            own.last
        }
        synchronized(lock) {
            val holder = held?.service?.get()
            if (holder === service) return
            if (tryShowFallback(service, own, notification)) {
                if (holder == null) manager(service)?.cancel(NOTIFICATION_ID)
            } else if (holder == null) {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            }
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
