package io.github.eedaegon.liveupdate

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
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
        // Before the notification: a test that sees it held looks for the service at once.
        running = this
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        val notification = intent?.let(::notificationOf)
        when {
            // As a recording service does: never out of the foreground, with its own notification when no
            // activity holds the placement.
            intent?.getBooleanExtra(EXTRA_FALLBACK, false) == true ->
                attachedOr = LiveUpdateForeground.attachOr(this, type, FALLBACK_ID) { fallback(this) }
            // As a host's service does: the library builds the notification of the stored activity.
            intent?.getBooleanExtra(EXTRA_ATTACH, false) == true ->
                if (!LiveUpdateForeground.attach(this, type)) stopSelf()
            intent == null || notification == null -> stopSelf()
            Build.VERSION.SDK_INT >= 29 -> startForeground(intent.getIntExtra(EXTRA_ID, 0), notification, type)
            else -> startForeground(intent.getIntExtra(EXTRA_ID, 0), notification)
        }
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
        private const val EXTRA_FALLBACK = "fallback"

        /** The id of the service's own notification, which it shows while no activity holds the placement. */
        const val FALLBACK_ID = 0x5E_0001

        /** What `attachOr` last answered, null before it was called. */
        @Volatile
        var attachedOr: Boolean? = null

        /** The service's own notification: built each time it is shown, as an application's is. */
        fun fallback(context: Context): Notification = Notification.Builder(context, Device.CHANNEL_ID)
            .setSmallIcon(R.drawable.liveupdate_status)
            .setContentTitle("Listening")
            .setOngoing(true)
            .build()

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

        /** Starts the service of this process, which attaches with its fallback. */
        fun attachOr(context: Context) {
            attachedOr = null
            context.startForegroundService(Intent(context, ForegroundProbeService::class.java).putExtra(EXTRA_FALLBACK, true))
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
                permit(true)
                Device.waitUntil("the notification listener is connected") { connected }
            }
            heard.clear()
        }

        /** The listener no longer hears, so that what one test leaves on its way does not reach the next. */
        internal fun disallow() {
            if (!connected) return
            permit(false)
            Device.waitUntil("the notification listener is disconnected") { !connected }
        }

        /** The device's listeners before this one was allowed, where they are a setting. */
        private var others: String? = null

        /**
         * Allows or disallows the listener. Android 8 has no shell command for it: below API 28 the listeners
         * are one setting, to which this one is added and from which it is taken again.
         */
        private fun permit(allowed: Boolean) {
            if (Build.VERSION.SDK_INT >= 28) {
                Device.shell("cmd notification ${if (allowed) "allow_listener" else "disallow_listener"} ${component()}")
                return
            }
            if (allowed) {
                others = Settings.Secure.getString(Device.context.contentResolver, LISTENERS)?.takeIf { it.isNotEmpty() }
            }
            val listeners = if (allowed) listOfNotNull(others, component()).joinToString(":") else others
            Device.shell(
                if (listeners == null) "settings delete secure $LISTENERS" else "settings put secure $LISTENERS $listeners",
            )
        }

        private const val LISTENERS = "enabled_notification_listeners"

        /** What was heard of the notification [key], oldest first. */
        internal fun heard(key: String): List<Heard> = heard.filter { it.key == key }

        private fun component(): String =
            ComponentName(Device.context, NotificationProbe::class.java).flattenToString()
    }
}
