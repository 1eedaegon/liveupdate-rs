package io.github.eedaegon.liveupdate

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.eedaegon.liveupdate.Device.context
import io.github.eedaegon.liveupdate.Device.dispatch
import io.github.eedaegon.liveupdate.Device.manager
import io.github.eedaegon.liveupdate.Device.v2Record
import io.github.eedaegon.liveupdate.Device.v2Snapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** A foreground service that is never without a notification: the activity's, or its own. */
@RunWith(AndroidJUnit4::class)
class FallbackInstrumentationTest {
    private val adapter = AndroidAdapter(context)
    private val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0

    @Before
    fun prepare() {
        Device.prepare()
        NotificationProbe.allow()
    }

    @After
    fun clear() {
        LiveUpdateForeground.setListener(null)
        ForegroundProbeService.running?.let(LiveUpdateForeground::detach)
        ForegroundProbeService.running?.stopSelf()
        Device.waitUntil("the service is gone") { ForegroundProbeService.running == null }
        NotificationProbe.disallow()
        Device.clear()
    }

    private fun foreground(id: String): JSONObject = v2Record(id, "standard", v2Snapshot(JSONObject().put("kind", "standard")))
        .apply { getJSONObject("origin").put("placement", "foreground") }

    private fun store(record: JSONObject): JSONObject {
        val receipt = JSONObject(record.toString()).put("reportedState", "unknown")
        dispatch("write", JSONObject().put("records", JSONArray().put(receipt)))
        return record
    }

    private fun shown(id: Int): StatusBarNotification? =
        manager.activeNotifications.firstOrNull { it.tag == null && it.id == id }

    private fun StatusBarNotification.isForeground(): Boolean =
        notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0

    private fun waitForeground(id: Int, what: String) =
        Device.waitUntil(what) { shown(id)?.isForeground() == true }

    // LU-FGS-9
    @Test
    fun withoutAnActivityTheServiceShowsItsOwnAndTakesTheActivityLater() {
        ForegroundProbeService.attachOr(context)
        waitForeground(ForegroundProbeService.FALLBACK_ID, "the fallback is the service's")
        assertEquals(false, ForegroundProbeService.attachedOr)
        assertNull(shown(LiveUpdateForeground.NOTIFICATION_ID))
        val fallbackKey = checkNotNull(shown(ForegroundProbeService.FALLBACK_ID)).key

        // Rust starts the activity, and the service attaches to it: one notification, the activity's.
        val ride = store(foreground("ride"))
        adapter.post(ride)
        Device.paceUpdate()
        assertTrue(LiveUpdateForeground.attach(checkNotNull(ForegroundProbeService.running), type))
        waitForeground(LiveUpdateForeground.NOTIFICATION_ID, "the activity's notification is the service's")
        Device.waitUntil("the fallback is gone") { shown(ForegroundProbeService.FALLBACK_ID) == null }
        assertTrue(NotificationProbe.heard(fallbackKey).last().removed)
        assertEquals(1, manager.activeNotifications.count { it.tag == null })
    }

    // LU-FGS-9
    @Test
    fun aSecondAttachOrThatFindsTheActivityTakesTheFallbackAway() {
        ForegroundProbeService.attachOr(context)
        waitForeground(ForegroundProbeService.FALLBACK_ID, "the fallback is the service's")

        // The activity begins, and a second tap starts the service again before anything attached.
        adapter.post(store(foreground("ride")))
        Device.paceUpdate()
        ForegroundProbeService.attachOr(context)
        Device.waitUntil("the second attachOr answered") { ForegroundProbeService.attachedOr == true }
        waitForeground(LiveUpdateForeground.NOTIFICATION_ID, "the activity's notification is the service's")
        Device.waitUntil("the fallback is gone") { shown(ForegroundProbeService.FALLBACK_ID) == null }
    }

    // LU-FGS-9
    @Test
    fun aFallbackUnderTheActivitysIdIsRefused() {
        val service = ForegroundProbeService.attachOr(context).let {
            Device.waitUntil("the service runs") { ForegroundProbeService.running != null }
            checkNotNull(ForegroundProbeService.running)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LiveUpdateForeground.attachOr(service, type, LiveUpdateForeground.NOTIFICATION_ID) {
                ForegroundProbeService.fallback(context)
            }
        }
    }

    // LU-FGS-9
    @Test
    fun withAnActivityTheServiceShowsItAndNeverItsOwn() {
        adapter.post(store(foreground("ride")))
        Device.paceUpdate()
        ForegroundProbeService.attachOr(context)
        waitForeground(LiveUpdateForeground.NOTIFICATION_ID, "the activity's notification is the service's")
        assertEquals(true, ForegroundProbeService.attachedOr)
        assertNull(shown(ForegroundProbeService.FALLBACK_ID))
    }

    // LU-FGS-10, LU-FGS-5
    @Test
    fun theEndOfTheActivityBringsTheServicesOwnBackWithoutLeavingTheForeground() {
        val released = CopyOnWriteArrayList<String>()
        LiveUpdateForeground.setListener { released += it }
        adapter.post(store(foreground("ride")))
        Device.paceUpdate()
        ForegroundProbeService.attachOr(context)
        waitForeground(LiveUpdateForeground.NOTIFICATION_ID, "the activity's notification is the service's")

        adapter.cancel("ride")
        waitForeground(ForegroundProbeService.FALLBACK_ID, "the fallback is the service's again")
        Device.waitUntil("the activity's notification is gone") { shown(LiveUpdateForeground.NOTIFICATION_ID) == null }
        assertEquals(listOf("ride"), released.toList())
        assertNotNull("the service runs on", ForegroundProbeService.running)
        // The service's notification came before the activity's went: never none, never two for long.
        val fallbackKey = checkNotNull(shown(ForegroundProbeService.FALLBACK_ID)).key
        assertFalse(NotificationProbe.heard(fallbackKey).any { it.removed })

        // A later activity takes the fallback's place again.
        Device.paceUpdate()
        adapter.post(store(foreground("ride-2")))
        assertTrue(LiveUpdateForeground.attach(checkNotNull(ForegroundProbeService.running), type))
        waitForeground(LiveUpdateForeground.NOTIFICATION_ID, "the second activity's notification is the service's")
        Device.waitUntil("the fallback is gone") { shown(ForegroundProbeService.FALLBACK_ID) == null }
    }

    // LU-FGS-10, LU-FGS-6
    @Test
    fun aServiceThatDetachedHasNoFallbackLeft() {
        adapter.post(store(foreground("ride")))
        Device.paceUpdate()
        ForegroundProbeService.attachOr(context)
        waitForeground(LiveUpdateForeground.NOTIFICATION_ID, "the activity's notification is the service's")
        LiveUpdateForeground.detach(checkNotNull(ForegroundProbeService.running))
        Device.waitUntil("the service left the foreground") { shown(LiveUpdateForeground.NOTIFICATION_ID)?.isForeground() == false }

        adapter.cancel("ride")
        Device.waitUntil("the activity's notification is gone") { shown(LiveUpdateForeground.NOTIFICATION_ID) == null }
        assertNull(shown(ForegroundProbeService.FALLBACK_ID))
    }
}
