package io.github.gon.liveupdate

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A host's foreground service: started with a notification and its id, it holds that notification
 * until a test stops it. It stands in for the service an application owns.
 */
open class ForegroundProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        val notification = intent?.let(::notificationOf)
        when {
            // As a host's service does: the library builds the notification of the stored activity.
            intent?.getBooleanExtra(EXTRA_ATTACH, false) == true ->
                if (!LiveUpdateForeground.attach(this, type)) stopSelf()
            intent == null || notification == null -> stopSelf()
            Build.VERSION.SDK_INT >= 29 -> startForeground(intent.getIntExtra(EXTRA_ID, 0), notification, type)
            else -> startForeground(intent.getIntExtra(EXTRA_ID, 0), notification)
        }
        running = this
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun notificationOf(intent: Intent): Notification? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_NOTIFICATION, Notification::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_NOTIFICATION)
        }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NOTIFICATION = "notification"
        private const val EXTRA_ATTACH = "attach"

        /** The service of this process while it is started; a service in another process is not seen here. */
        @Volatile
        var running: ForegroundProbeService? = null
            private set

        fun start(context: Context, service: Class<out ForegroundProbeService>, id: Int, notification: Notification) {
            val intent = Intent(context, service).putExtra(EXTRA_ID, id).putExtra(EXTRA_NOTIFICATION, notification)
            context.startForegroundService(intent)
        }

        /**
         * Stops the service of this process and removes the notification under [id], and returns once both are
         * done. Android posts the notification of a service that detached once more, a moment later; a removal
         * by the service is ordered after that post, where a cancel by the application can come before it.
         */
        fun stop(id: Int, notification: Notification) {
            val service = running ?: return
            val shown = { Device.manager.activeNotifications.any { it.tag == null && it.id == id } }
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
            Device.paceUpdate()
            if (Build.VERSION.SDK_INT >= 29) {
                service.startForeground(id, notification, type)
            } else {
                service.startForeground(id, notification)
            }
            Device.waitUntil("the stopping service shows its notification") { shown() }
            service.stopForeground(STOP_FOREGROUND_REMOVE)
            Device.waitUntil("the stopping service's notification is gone") { !shown() }
            service.stopSelf()
            Device.waitUntil("the service is destroyed") { running == null }
        }

        /** Starts the service of this process, which attaches to the stored foreground activity. */
        fun attach(context: Context) {
            val intent = Intent(context, ForegroundProbeService::class.java).putExtra(EXTRA_ATTACH, true)
            context.startForegroundService(intent)
        }
    }
}

/** [ForegroundProbeService] in the process `:probe`. */
class RemoteForegroundProbeService : ForegroundProbeService()

/** One post or removal of a notification of the test package, as a listener heard it. */
internal data class Heard(val key: String, val removed: Boolean, val flags: Int)

/**
 * Hears every post and removal of the test package's notifications, in order. Reading the active
 * notifications cannot tell a notification that was replaced from one that was removed and posted
 * again; this can.
 */
class NotificationProbe : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(notification: StatusBarNotification) = hear(notification, removed = false)

    override fun onNotificationRemoved(notification: StatusBarNotification) = hear(notification, removed = true)

    private fun hear(notification: StatusBarNotification, removed: Boolean) {
        if (notification.packageName != packageName) return
        heard += Heard(notification.key, removed, notification.notification.flags)
    }

    companion object {
        private val heard = CopyOnWriteArrayList<Heard>()

        @Volatile
        private var connected = false

        /** Lets the listener hear, and forgets what it heard before. It stays connected until [disallow]. */
        internal fun allow() {
            if (!connected) {
                Device.shell("cmd notification allow_listener ${component()}")
                Device.waitUntil("the notification listener is connected") { connected }
            }
            heard.clear()
        }

        /** The listener no longer hears, so that what one test leaves on its way does not reach the next. */
        internal fun disallow() {
            if (!connected) return
            Device.shell("cmd notification disallow_listener ${component()}")
            Device.waitUntil("the notification listener is disconnected") { !connected }
        }

        /** What was heard of the notification [key], oldest first. */
        internal fun heard(key: String): List<Heard> = heard.filter { it.key == key }

        private fun component(): String =
            ComponentName(Device.context, NotificationProbe::class.java).flattenToString()
    }
}
