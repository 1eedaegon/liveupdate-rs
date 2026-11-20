package io.github.eedaegon.liveupdate.consumer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.eedaegon.liveupdate.LiveUpdateActions
import io.github.eedaegon.liveupdate.LiveUpdateForeground

/**
 * A recorder, as Fremen's is: a foreground service that the Record action of the live activity starts or reaches
 * directly, also with no activity and from the lock screen. It is in the foreground at once, with the activity's
 * notification or its own, takes the tap, and updates the activity through the application's Rust from its own
 * thread, without `ndk-context`.
 */
class RecorderService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        val attached = LiveUpdateForeground.attachOr(this, type, FALLBACK_ID) { fallback() }
        val tap = LiveUpdateActions.take(this, intent)
        report("recorder", "{\"attached\":$attached,\"tap\":\"$tap\"}")
        if (tap != null && !tap.withheld && tap.actionId == "record") {
            recording = !recording
            val context = applicationContext
            Thread { report("recording", Native.runFrom(context, "recording", "{\"on\":$recording}")) }.start()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    private fun fallback(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Recorder", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.consumer_status)
            .setContentTitle("Recorder")
            .setContentText(if (recording) "Recording" else "Listening")
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val FALLBACK_ID = 0x0C_0001
        const val CHANNEL = "recorder"

        @Volatile
        var recording = false
    }
}
