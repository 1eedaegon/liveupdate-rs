package io.github.eedaegon.liveupdate

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.ongoing.SerializationHelper
import androidx.wear.ongoing.Status
import io.github.eedaegon.liveupdate.Device.context
import io.github.eedaegon.liveupdate.Device.v2Record
import io.github.eedaegon.liveupdate.Device.v2Snapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Ongoing Activity of a watch, which the tests ask for on any device: the test package has the library. */
@RunWith(AndroidJUnit4::class)
class WearOngoingInstrumentationTest {
    private val offered = AndroidAdapter(context, wearOngoing = true)
    private val withheld = AndroidAdapter(context, wearOngoing = false)
    private val standard = JSONObject().put("kind", "standard")

    @Before
    fun prepare() = Device.prepare()

    @After
    fun clear() = Device.clear()

    // LU-WEAR-1, LU-WEAR-2
    @Test
    fun aNotificationOfALiveActivityCarriesAnOngoingActivityWhereItIsOffered() {
        withheld.post(v2Record("ride", "standard", v2Snapshot(standard)))
        assertFalse(SerializationHelper.hasOngoingActivity(Device.waitForShown("ride", revision = 0)))

        Device.paceUpdate()
        offered.post(v2Record("ride", "standard", v2Snapshot(standard, revision = 1)))
        val shown = Device.waitForShown("ride", revision = 1)
        val ongoing = checkNotNull(SerializationHelper.create(shown))
        assertEquals(shown.smallIcon.resId, ongoing.staticIcon.resId)
        assertNotNull(shown.contentIntent)
        assertEquals(shown.contentIntent, ongoing.touchIntent)
        assertEquals("Mochi's snack delivery", ongoing.title)
        assertEquals(Notification.CATEGORY_STATUS, ongoing.category)
        assertEquals("Preparing", statusOf(shown))

        // Every post builds it anew.
        Device.paceUpdate()
        offered.post(v2Record("ride", "standard", v2Snapshot(standard, revision = 2).put("status", "Arriving")))
        assertEquals("Arriving", statusOf(Device.waitForShown("ride", revision = 2)))
    }

    // LU-WEAR-3
    @Test
    fun aHeaderClockBecomesATimerOrAStopwatchPartOfTheStatus() {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        fun clocked(id: String, countsDown: Boolean): Status {
            val timer = Device.timer(now - 60_000, now + 600_000)
            val chronometer = JSONObject().put("timer", timer).put("countsDown", countsDown)
            offered.post(v2Record(id, "standard", v2Snapshot(standard, chronometer = chronometer)))
            return checkNotNull(checkNotNull(SerializationHelper.create(Device.waitForShown(id))).status)
        }

        val down = clocked("down", countsDown = true)
        val timer = down.getPart("time") as Status.TimerPart
        assertTrue(timer.isCountDown)
        assertEquals((elapsed + 600_000).toDouble(), timer.timeZeroMillis.toDouble(), 2_000.0)
        assertEquals("Preparing 10:00", down.getText(context, timer.timeZeroMillis - 600_000).toString())

        val up = clocked("up", countsDown = false)
        val stopwatch = up.getPart("time") as Status.StopwatchPart
        assertFalse(stopwatch.isCountDown)
        assertEquals((elapsed - 60_000).toDouble(), stopwatch.timeZeroMillis.toDouble(), 2_000.0)
        assertEquals("Preparing 01:00", up.getText(context, stopwatch.timeZeroMillis + 60_000).toString())
    }

    // LU-WEAR-1
    @Test
    fun theLastContentAndAHeldNotificationFollowTheRule() {
        val record = v2Record("done", "standard", v2Snapshot(standard))
        offered.post(record)
        assertTrue(SerializationHelper.hasOngoingActivity(Device.waitForShown("done")))

        // The last content of an ended activity is not ongoing.
        Device.paceUpdate()
        val ended = JSONObject(record.toString()).put("state", "ended").put("awaitingObservation", false)
            .put("dismissAt", System.currentTimeMillis() + 60_000)
        offered.post(ended)
        Device.waitUntil("the last content is shown") {
            Device.shown("done")?.notification?.let { it.flags and Notification.FLAG_ONGOING_EVENT == 0 } == true
        }
        assertFalse(SerializationHelper.hasOngoingActivity(checkNotNull(Device.shown("done")).notification))

        // What a foreground service of the host holds is built from the stored receipt, the same way.
        val held = v2Record("held", "standard", v2Snapshot(standard))
            .apply { getJSONObject("origin").put("placement", "foreground") }
        val receipt = JSONObject(held.toString()).put("reportedState", "unknown")
        Device.dispatch("write", JSONObject().put("records", JSONArray().put(receipt)))
        assertTrue(SerializationHelper.hasOngoingActivity(checkNotNull(offered.foregroundNotification()).second))
        assertFalse(SerializationHelper.hasOngoingActivity(checkNotNull(withheld.foregroundNotification()).second))
    }

    // LU-WEAR-1
    @Test
    fun aLibraryThatFailsLeavesTheNotificationAsItIs() {
        val record = v2Record("plain", "standard", v2Snapshot(standard))
        // An exception of the library, and a member that the application's version of it lacks.
        val failures = listOf<() -> Unit>(
            { throw IllegalStateException("the library") },
            { throw NoSuchMethodError("the library") },
        )
        for (failure in failures) {
            val renderer = NotificationRenderer(context, Build.VERSION.SDK_INT, Device.CHANNEL_ID, Build.MANUFACTURER) { _, _ ->
                failure()
            }
            val notification = renderer.build(record)
            assertFalse(SerializationHelper.hasOngoingActivity(notification))
            assertEquals("Mochi's snack delivery", notification.extras.getString(Notification.EXTRA_TITLE))
        }
    }

    // LU-WEAR-4
    @Test
    fun theCapabilityFollowsWhatIsOffered() {
        fun capability(adapter: AndroidAdapter): Boolean =
            adapter.capabilities().getJSONObject("presentation").getBoolean("wearOngoingActivity")
        assertTrue(capability(offered))
        assertFalse(capability(withheld))

        // The device's own answer: a watch of API 30 to 36, as the test package has the library.
        assertTrue(hasWearOngoingLibrary())
        val watch = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
        assertEquals(watch && Build.VERSION.SDK_INT in 30..36, capability(AndroidAdapter(context)))
    }

    private fun statusOf(notification: Notification): String {
        val status = checkNotNull(checkNotNull(SerializationHelper.create(notification)).status)
        return status.getText(context, SystemClock.elapsedRealtime()).toString()
    }
}
