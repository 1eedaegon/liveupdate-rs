package io.github.eedaegon.liveupdate

import android.app.ActivityManager
import android.app.Notification
import android.app.Service
import android.content.Context
import android.os.Build
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.eedaegon.liveupdate.Device.context
import io.github.eedaegon.liveupdate.Device.manager
import io.github.eedaegon.liveupdate.Device.v2Record
import io.github.eedaegon.liveupdate.Device.v2Snapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What Android does with a notification that a foreground service holds. The foreground placement of
 * docs/design/android-core-extensions rests on each of these: a notification of the adapter, posted
 * without a tag, is held by a service of the host.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundServiceInstrumentationTest {
    private companion object {
        /** Any id without a tag: `startForeground` takes no tag. */
        const val FOREGROUND_ID = 0x4C55_0001
    }

    @Before
    fun prepare() {
        Device.prepare()
        // The channel of the adapter's notifications.
        AndroidAdapter(context).capabilities()
        NotificationProbe.allow()
    }

    @After
    fun clear() {
        ForegroundProbeService.stop(FOREGROUND_ID, built(revision = 0))
        remoteProcess()?.let(Process::killProcess)
        Device.clear()
        NotificationProbe.disallow()
    }

    // LU-FGS-8
    @Test
    fun aPostedNotificationBecomesTheServicesWithoutBeingRemoved() {
        val notification = built(revision = 0)
        manager.notify(FOREGROUND_ID, notification)
        val posted = waitForHeld { !it.isForeground() }

        Device.paceUpdate()
        ForegroundProbeService.start(context, ForegroundProbeService::class.java, FOREGROUND_ID, notification)
        val held = waitForHeld { it.isForeground() }

        assertEquals(posted.key, held.key)
        assertEquals(1, manager.activeNotifications.size)
        waitForHeard(held.key, "the listener heard the service's post") { heard ->
            heard.any { it.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }
        }
        val heard = NotificationProbe.heard(held.key)
        assertTrue(heard.toString(), heard.none { it.removed })
        assertTrue(heard.toString(), heard.size >= 2)
        assertEquals(0L, revisionOf(held))
        assertTrue(Build.VERSION.SDK_INT >= 37)
        assertTrue(posted.notification.hasPromotableCharacteristics())
        assertTrue(held.notification.hasPromotableCharacteristics())
    }

    // LU-FGS-8
    @Test
    fun aPostUnderTheSameIdKeepsTheServiceAndACancelDoesNothing() {
        ForegroundProbeService.start(context, ForegroundProbeService::class.java, FOREGROUND_ID, built(revision = 0))
        val held = waitForHeld { it.isForeground() }

        Device.paceUpdate()
        manager.notify(FOREGROUND_ID, built(revision = 1))
        val updated = waitForHeld { revisionOf(it) == 1L }
        assertTrue(updated.isForeground())
        assertEquals(held.key, updated.key)

        manager.cancel(FOREGROUND_ID)
        // A cancel that Android took would be heard as a removal; a later post proves it had its turn.
        Device.paceUpdate()
        manager.notify(FOREGROUND_ID, built(revision = 2))
        val after = waitForHeld { revisionOf(it) == 2L }
        assertTrue(after.isForeground())
        assertTrue(NotificationProbe.heard(held.key).none { it.removed })
    }

    // LU-FGS-8
    @Test
    fun leavingTheForegroundRemovesTheNotificationOrLeavesItAnOrdinaryOne() {
        ForegroundProbeService.start(context, ForegroundProbeService::class.java, FOREGROUND_ID, built(revision = 0))
        val held = waitForHeld { it.isForeground() }
        val service = checkNotNull(ForegroundProbeService.running)

        // Detached, the notification stays and is no longer the service's. Android posts the notification that
        // the service was last given once more, a moment later: what the application posted past the service
        // since is replaced by it, and a cancel in between is undone. So the service is given the newest content
        // before it detaches, and nothing here relies on a cancel after a detach.
        Device.paceUpdate()
        showThroughService(service, built(revision = 5))
        waitForHeld { it.isForeground() && revisionOf(it) == 5L }
        service.stopForeground(Service.STOP_FOREGROUND_DETACH)
        val detached = waitForHeld { !it.isForeground() }
        assertEquals(held.key, detached.key)
        assertEquals(5L, revisionOf(detached))
        assertTrue(NotificationProbe.heard(held.key).none { it.removed })

        // The service takes it again, and removes it when it leaves the foreground with it.
        Device.paceUpdate()
        ForegroundProbeService.start(context, ForegroundProbeService::class.java, FOREGROUND_ID, built(revision = 1))
        waitForHeld { it.isForeground() && revisionOf(it) == 1L }
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        Device.waitUntil("the removed notification is gone") { held() == null }
    }

    // LU-FGS-8
    @Test
    fun aKilledProcessTakesItsForegroundNotificationWithIt() {
        ForegroundProbeService.start(
            context,
            RemoteForegroundProbeService::class.java,
            FOREGROUND_ID,
            built(revision = 0),
        )
        val held = waitForHeld { it.isForeground() }
        assertNull("the remote service runs in another process", ForegroundProbeService.running)
        val process = remoteProcess()
        assertNotNull(process)

        Process.killProcess(checkNotNull(process))

        Device.waitUntil("the dead process's notification is gone") { held() == null }
    }

    /**
     * A notification as the adapter builds it for a promoted schema-2 record, shown at once: Android
     * otherwise holds a service's notification back for ten seconds.
     */
    private fun built(revision: Long): Notification {
        val style = JSONObject().put("kind", "standard")
        val record = v2Record("foreground", "standard", v2Snapshot(style, revision = revision))
        val notification = NotificationRenderer(context, Build.VERSION.SDK_INT, Device.CHANNEL_ID, Build.MANUFACTURER)
            .build(record)
        return Notification.Builder.recoverBuilder(context, notification)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun showThroughService(service: Service, notification: Notification) {
        service.startForeground(
            FOREGROUND_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
        )
    }

    /** The notification without a tag under the foreground id, which is how a service holds one. */
    private fun held(): StatusBarNotification? =
        manager.activeNotifications.firstOrNull { it.tag == null && it.id == FOREGROUND_ID }

    private fun waitForHeld(condition: (StatusBarNotification) -> Boolean): StatusBarNotification {
        var found: StatusBarNotification? = null
        Device.waitUntil("the foreground notification is as expected") {
            found = held()?.takeIf(condition)
            found != null
        }
        return checkNotNull(found)
    }

    /** Waits for what the listener heard of [key]; a timeout says what it did hear. */
    private fun waitForHeard(key: String, what: String, condition: (List<Heard>) -> Boolean) {
        try {
            Device.waitUntil(what) { condition(NotificationProbe.heard(key)) }
        } catch (error: AssertionError) {
            throw AssertionError("$what: heard ${NotificationProbe.heard(key)}", error)
        }
    }

    private fun StatusBarNotification.isForeground(): Boolean =
        notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0

    private fun revisionOf(notification: StatusBarNotification): Long =
        JSONObject(checkNotNull(notification.notification.extras.getString(RECORD_EXTRA)))
            .getJSONObject("snapshot").getLong("revision")

    /** The process id of `:probe`, where [RemoteForegroundProbeService] runs. */
    private fun remoteProcess(): Int? {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activity.runningAppProcesses.orEmpty()
            .firstOrNull { it.processName == "${context.packageName}:probe" }?.pid
    }
}
