package io.github.eedaegon.liveupdate

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.LinkedBlockingQueue

/** What a component that an action's target names was given: its class, the intent's action, and the tap. */
internal data class Taken(val component: String, val intentAction: String?, val tap: LiveUpdateTap?)

/**
 * A service of the application that an action's target names. As a host's must, it takes each tap first with
 * `LiveUpdateActions.take`. The test package's id is not the Java package of its classes, as a Tauri
 * application's is not.
 */
open class TargetProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        taps.put(Taken(javaClass.name, intent?.action, LiveUpdateActions.take(this, intent)))
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        internal val taps = LinkedBlockingQueue<Taken>()
    }
}

/** The same, started in the foreground: it has to call `startForeground` within Android's few seconds. */
class TargetProbeForegroundService : TargetProbeService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, Device.CHANNEL_ID)
            .setSmallIcon(R.drawable.liveupdate_status)
            .setContentTitle("Recording")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
        val answer = super.onStartCommand(intent, flags, startId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        return answer
    }

    private companion object {
        const val FOREGROUND_ID = 0x7A_0001
    }
}

/** A service that is exported, which a target may not name. */
class TargetProbeExportedService : TargetProbeService()

/** A service in another process, which a target may not name. */
class TargetProbeRemoteService : TargetProbeService()

/** A receiver of the application that an action's target names. */
class TargetProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TargetProbeService.taps.put(Taken(javaClass.name, intent.action, LiveUpdateActions.take(context, intent)))
    }
}
