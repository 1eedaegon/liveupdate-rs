package io.github.eedaegon.liveupdate

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.eedaegon.liveupdate.Device.context
import io.github.eedaegon.liveupdate.Device.manager
import io.github.eedaegon.liveupdate.Device.v2Record
import io.github.eedaegon.liveupdate.Device.v2Snapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A second notification of an activity, not ongoing, which a paired watch's bridge takes. */
@RunWith(AndroidJUnit4::class)
class MirrorInstrumentationTest {
    private val adapter = AndroidAdapter(context)

    @Before
    fun prepare() = Device.prepare()

    @After
    fun clear() = Device.clear()

    private fun record(id: String, mirrored: Boolean, revision: Long = 0, alert: Boolean = false) = v2Record(
        id,
        "standard",
        v2Snapshot(JSONObject().put("kind", "standard"), revision).apply {
            val presentation = getJSONObject("presentation")
            if (mirrored) presentation.put("watchMirror", true)
            if (alert) presentation.put("alert", true)
        },
    )

    private fun ended(record: JSONObject, dismissAt: Long): JSONObject = JSONObject(record.toString())
        .put("state", "ended").put("awaitingObservation", false).put("dismissAt", dismissAt)

    private fun mirror(id: String): StatusBarNotification? =
        manager.activeNotifications.firstOrNull { it.tag == MIRROR_TAG + id && it.id == Device.NOTIFICATION_ID }

    private fun waitForMirror(id: String, revision: Long = 0): Notification {
        Device.waitUntil("the mirror of $id at revision $revision") {
            mirror(id)?.notification?.extras?.getString(Notification.EXTRA_TEXT) != null &&
                mirror(id)?.notification?.extras?.getLong(MIRROR_REVISION, -1) == revision
        }
        return checkNotNull(mirror(id)).notification
    }

    // LU-MIRROR-3
    @Test
    fun aMirrorIsPostedBesideTheActivityAndTheActivityStaysOnThePhone() {
        assertTrue(adapter.capabilities().getJSONObject("presentation").getBoolean("watchMirror"))
        adapter.post(record("ride", mirrored = true))
        val own = Device.waitForShown("ride")
        val mirror = waitForMirror("ride")
        // The activity's own notification is the phone's: a watch that bridges a Live Update shows one card.
        assertTrue(own.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertTrue(own.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(0, mirror.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(0, mirror.flags and Notification.FLAG_LOCAL_ONLY)
        assertFalse(mirror.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        // The bridge carries the extras to the watch: the receipt stays on the phone.
        assertNull(mirror.extras.getString(RECORD_EXTRA))
        assertEquals(own.extras.getString(Notification.EXTRA_TITLE), mirror.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(own.extras.getString(Notification.EXTRA_TEXT), mirror.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(MIRROR_CHANNEL, mirror.channelId)
        val channel = checkNotNull(manager.getNotificationChannel(MIRROR_CHANNEL))
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel.importance)
        assertEquals("Test rides on a watch", channel.name)
        // The lifecycle sees the activity once.
        assertEquals(1, Device.observed().length())
        assertEquals(setOf("ride"), adapter.listed())

        adapter.post(record("plain", mirrored = false))
        val plain = Device.waitForShown("plain")
        assertEquals(0, plain.flags and Notification.FLAG_LOCAL_ONLY)
        assertNull(mirror("plain"))
    }

    // LU-MIRROR-4
    @Test
    fun aRevisionThatAlertsAlertsThroughTheMirrorAlone() {
        adapter.post(record("ride", mirrored = true))
        waitForMirror("ride")
        Device.paceUpdate()
        adapter.post(record("ride", mirrored = true, revision = 1, alert = true))
        val own = Device.waitForShown("ride", revision = 1)
        val mirror = waitForMirror("ride", revision = 1)
        assertEquals(Device.CHANNEL_ID, own.channelId)
        assertEquals(ALERT_CHANNEL, mirror.channelId)
        Device.paceUpdate()
        adapter.post(record("ride", mirrored = true, revision = 2))
        assertEquals(MIRROR_CHANNEL, waitForMirror("ride", revision = 2).channelId)
    }

    // LU-MIRROR-5
    @Test
    fun aMirrorGoesWithItsActivity() {
        adapter.post(record("ride", mirrored = true))
        waitForMirror("ride")
        // An update without it takes it away, and one with it brings it back.
        Device.paceUpdate()
        adapter.post(record("ride", mirrored = false, revision = 1))
        Device.waitForShown("ride", revision = 1)
        Device.waitUntil("the mirror is gone") { mirror("ride") == null }
        Device.paceUpdate()
        val live = record("ride", mirrored = true, revision = 2)
        adapter.post(live)
        waitForMirror("ride", revision = 2)

        // The last content: on the mirror too, not ongoing, and gone at the same time.
        Device.paceUpdate()
        adapter.post(ended(live, System.currentTimeMillis() + 60_000))
        Device.waitUntil("the mirror is the last content") {
            mirror("ride")?.notification?.let { it.flags and Notification.FLAG_ONGOING_EVENT == 0 && it.timeoutAfter > 0 } == true
        }
        adapter.post(ended(live, System.currentTimeMillis() - 1))
        Device.waitForGone("ride")
        Device.waitUntil("the mirror is gone with it") { mirror("ride") == null }

        adapter.post(record("other", mirrored = true))
        waitForMirror("other")
        adapter.cancel("other")
        Device.waitUntil("a cancel takes the mirror") { mirror("other") == null }
    }

    // LU-MIRROR-6
    @Test
    fun aPersonsDismissalOfTheMirrorStopsIt() {
        adapter.post(record("ride", mirrored = true))
        val mirror = waitForMirror("ride")
        // As a swipe on the watch does, through the bridge: the phone's mirror is cancelled, and its intent sent.
        manager.cancel(MIRROR_TAG + "ride", Device.NOTIFICATION_ID)
        checkNotNull(mirror.deleteIntent).send()
        Device.waitUntil("the dismissal is kept") {
            context.getSharedPreferences(Device.PREFERENCES, 0).getStringSet(DISMISSED_MIRRORS, null)?.contains("ride") == true
        }
        Device.paceUpdate()
        adapter.post(record("ride", mirrored = true, revision = 1))
        Device.waitForShown("ride", revision = 1)
        Thread.sleep(1_000)
        assertNull(mirror("ride"))
        // Not an event of the activity: the person did not dismiss the activity.
        assertEquals(0, EventStore(context).size())
        // The activity's end forgets it.
        adapter.cancel("ride")
        assertFalse(
            context.getSharedPreferences(Device.PREFERENCES, 0).getStringSet(DISMISSED_MIRRORS, emptySet())!!.contains("ride"),
        )
    }

    private companion object {
        const val MIRROR_TAG = "liveupdate-rs-watch:"
        const val MIRROR_CHANNEL = "liveupdate-rs.watch.v1"
        const val ALERT_CHANNEL = "liveupdate-rs.alerts.v1"
        const val MIRROR_REVISION = "io.github.1eedaegon.liveupdate.mirrorRevision"
        const val DISMISSED_MIRRORS = "mirrorsDismissed"
    }
}
