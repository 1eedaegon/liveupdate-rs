package io.github.eedaegon.liveupdate

import android.app.AlarmManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A start that names its time. The lifecycle stores the receipt and the adapter begins it: here the receipts
 * are stored as the lifecycle stores them, and the adapter is asked through the public bridge.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledStartInstrumentationTest {
    private companion object {
        /** Far enough ahead to see that nothing is shown before, near enough for an alarm of the emulator. */
        const val AHEAD = 5_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }

    @Before
    fun prepare() = Device.prepare()

    @After
    fun clear() = Device.clear()

    // LU-SCHED-10
    @Test
    fun aScheduledStartIsPostedAtItsTimeFromTheStoredReceipt() {
        val startAt = System.currentTimeMillis() + AHEAD
        val record = scheduled("ride", startAt)
        val pending = JSONObject(record.toString()).put("postFailed", true)

        // In the lifecycle's order: the receipt is stored unconfirmed, scheduled, asked for, and confirmed.
        store(pending)
        dispatch("schedule", JSONObject().put("record", pending))
        assertNull(Device.shown("ride"))
        // It waits: asking begins nothing and reports nothing.
        assertTrue(starts().isEmpty())
        store(record)
        assertNull(Device.shown("ride"))
        // Nothing but the alarm posts it, and nothing is asked after the start.
        val shown = Device.waitForShown("ride")
        assertTrue(System.currentTimeMillis() >= startAt)
        assertTrue(shown.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val observed = Device.observed()
        assertEquals(1, observed.length())
        assertEquals("ride", observed.getJSONObject(0).getString("id"))
        assertEquals(
            Device.canonical(record.getJSONObject("snapshot")),
            Device.canonical(observed.getJSONObject(0).getJSONObject("snapshot")),
        )
        // Android lists it: there is nothing to report, and it is not posted again.
        assertTrue(starts().isEmpty())
    }

    // LU-SCHED-10
    @Test
    fun aTimeThatHasPassedIsPostedAtOnceAndALostAlarmIsMadeUpForWhenTheAdapterIsAsked() {
        val now = System.currentTimeMillis()
        val late = store(scheduled("late", now - 1_000))
        dispatch("schedule", JSONObject().put("record", late))
        Device.waitForShown("late")

        // A receipt that became due without an alarm, as after a restart of the device.
        val lost = scheduled("lost", now - 1_000)
        store(late, lost)
        assertNull(Device.shown("lost"))
        starts()
        Device.waitForShown("lost")

        // A scheduling that the lifecycle has not confirmed begins nothing, until it is scheduled again.
        val pending = scheduled("pending", now - 1_000).put("postFailed", true)
        store(late, lost, pending)
        assertNull(starts()["pending"])
        Device.paceUpdate()
        assertNull(Device.shown("pending"))
        dispatch("schedule", JSONObject().put("record", pending))
        Device.waitForShown("pending")
    }

    // LU-SCHED-9
    @Test
    fun whatAPostWouldRefuseIsRefusedWhenItIsScheduled() {
        val now = System.currentTimeMillis()
        val later = now + HOUR
        fun clock(startedAt: Long) = JSONObject()
            .put("timer", Device.timer(startedAt, now + 30 * DAY)).put("countsDown", false)
        val refused = listOf(
            scheduled("not-scheduled", later).put("state", "unknown"),
            scheduled("no-time", later).apply { getJSONObject("origin").remove("startAt") },
            scheduled("negative", -1),
            scheduled("too-far", 8_640_000_000_000_001),
            scheduled("foreground", later).apply { getJSONObject("origin").put("placement", "foreground") },
            Device.record("schema-1").put("state", "scheduled").put("awaitingObservation", false)
                .apply { getJSONObject("origin").put("startAt", later) },
            scheduled("no-link", later) { put("openUrl", "nobody-opens-this://x") },
            scheduled("old-clock", later) { put("chronometer", clock(now - 15 * DAY)) },
            // A clock that Android still takes now, and no longer at the start's time.
            scheduled("old-by-then", now + 2 * DAY) { put("chronometer", clock(now - 13 * DAY)) },
        )
        for (record in refused) {
            store(record)
            Device.failure("schedule", JSONObject().put("record", record), "INVALID_INPUT")
        }
        // The alarm is set for the stored receipt: one that is not stored, or is stored as begun, has none.
        val begun = scheduled("begun", later)
        store(JSONObject(begun.toString()).put("state", "active"))
        Device.failure("schedule", JSONObject().put("record", begun), "INVALID_INPUT")
        store()
        Device.failure("schedule", JSONObject().put("record", scheduled("not-stored", later)), "INVALID_INPUT")
        assertEquals(0, manager.activeNotifications.size)

        // A start far ahead is taken, with a clock that is young then: only a clock has an age.
        val far = store(
            scheduled("far", now + 20 * DAY),
            scheduled("far-clock", now + 20 * DAY) { put("chronometer", clock(now + 19 * DAY)) },
        )
        dispatch("schedule", JSONObject().put("record", far))
        assertTrue(starts().isEmpty())
        assertEquals(0, manager.activeNotifications.size)
    }

    // LU-SCHED-11
    @Test
    fun aStartThatAndroidDoesNotListIsReportedAsPostedForAMomentAndThenAsGone() {
        val now = System.currentTimeMillis()
        val patient = AndroidAdapter(context, listingGrace = HOUR)
        val impatient = AndroidAdapter(context, listingGrace = 0)
        store(scheduled("removed", now - 1_000), scheduled("unpostable", now - 1_000) {
            put("openUrl", "nobody-opens-this://x")
        })

        // The first ask begins what is due. A post that failed is gone at once.
        assertEquals("gone", outcomes(patient.starts())["unpostable"])
        Device.waitForShown("removed")
        assertNull(Device.shown("unpostable"))
        assertEquals(mapOf("unpostable" to "gone"), outcomes(patient.starts()))

        // Removed, as by the person: posted a moment ago for an adapter that still waits for the listing,
        // gone for one that no longer does. It is not posted again.
        manager.cancel(Device.TAG_PREFIX + "removed", Device.NOTIFICATION_ID)
        Device.waitForGone("removed")
        assertEquals("posted", outcomes(patient.starts())["removed"])
        assertEquals("gone", outcomes(impatient.starts())["removed"])
        assertNull(Device.shown("removed"))

        // What is no longer scheduled is forgotten: the lifecycle has stored what became of it.
        store(scheduled("removed", now - 1_000).put("state", "dismissed"))
        assertTrue(outcomes(impatient.starts()).isEmpty())
        assertNull(Device.shown("removed"))

        // The notes are the adapter's own: one that cannot be read fails nothing.
        context.getSharedPreferences(Device.PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString("starts", "not json").commit()
        assertTrue(outcomes(impatient.starts()).isEmpty())
        dispatch("cancel", JSONObject().put("id", "removed"))
    }

    // LU-SCHED-12
    @Test
    fun anEndBeforeTheTimeLeavesNothingAndTheNextStartKeepsItsAlarm() {
        val now = System.currentTimeMillis()
        val first = scheduled("first", now + AHEAD)
        val second = scheduled("second", now + 2 * AHEAD)
        store(first, second)
        dispatch("schedule", JSONObject().put("record", first))
        dispatch("schedule", JSONObject().put("record", second))

        // The lifecycle stores the end, then cancels.
        store(JSONObject(first.toString()).put("state", "ended"), second)
        dispatch("cancel", JSONObject().put("id", "first"))

        Device.waitForShown("second")
        assertTrue(System.currentTimeMillis() >= now + 2 * AHEAD)
        assertNull(Device.shown("first"))
    }

    // LU-SCHED-2, LU-SCHED-12
    @Test
    fun theAlarmIsExactWhereTheApplicationMaySetOneAndInexactOtherwise() {
        assertTrue(Build.VERSION.SDK_INT >= 31)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val presentation = dispatch("capabilities").getJSONObject("data").getJSONObject("presentation")
        assertTrue(presentation.getBoolean("scheduledStart"))
        // The test package targets API 26, which may set exact alarms without a permission.
        assertTrue(alarms.canScheduleExactAlarms())
        assertTrue(presentation.getBoolean("exactStart"))

        // An application that may not, as most that target a newer API: two inexact alarms, and it begins.
        val inexact = AndroidAdapter(context, exactAlarms = false)
        assertFalse(inexact.capabilities().getJSONObject("presentation").getBoolean("exactStart"))
        val loose = store(scheduled("loose", System.currentTimeMillis() + AHEAD))
        inexact.schedule(loose)
        assertNull(Device.shown("loose"))
        Device.waitForShown("loose")

        // One that may: the exact alarm comes at its time.
        val startAt = System.currentTimeMillis() + AHEAD
        val exact = store(loose, scheduled("exact", startAt))
        dispatch("schedule", JSONObject().put("record", exact))
        Device.waitForShown("exact")
        val late = System.currentTimeMillis() - startAt
        assertTrue("$late ms after its time", late in 0 until 10_000)
    }

    // LU-SCHED-13
    @Test
    fun aRestartOfTheDeviceSetsTheAlarmAgainForAHostThatDeclaredTheReceiver() {
        val receiver = LiveUpdateStartReceiver()
        val declared = context.packageManager
            .queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName), 0)
            .single().activityInfo
        assertEquals(LiveUpdateStartReceiver::class.java.name, declared.name)
        assertFalse(declared.exported)

        // No alarm is set for these receipts, as after a restart. Another broadcast begins nothing.
        val now = System.currentTimeMillis()
        val startAt = now + AHEAD
        store(scheduled("due", now - 1_000), scheduled("after-restart", startAt))
        receiver.onReceive(context, Intent("io.github.1eedaegon.liveupdate.OTHER"))
        Device.paceUpdate()
        assertEquals(0, manager.activeNotifications.size)

        // The restart begins what is due and sets the alarm for what waits.
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        Device.waitForShown("due")
        assertNull(Device.shown("after-restart"))
        Device.waitForShown("after-restart")
        assertTrue(System.currentTimeMillis() >= startAt)
    }

    /** A receipt as the lifecycle stores it for a start at [startAt]. */
    private fun scheduled(id: String, startAt: Long, configure: JSONObject.() -> Unit = {}): JSONObject {
        val snapshot = v2Snapshot(JSONObject().put("kind", "standard"))
        snapshot.getJSONObject("presentation").configure()
        return v2Record(id, "standard", snapshot)
            .put("state", "scheduled")
            .put("awaitingObservation", false)
            .apply { getJSONObject("origin").put("startAt", startAt) }
    }

    /**
     * Stores [records] alone, as the lifecycle does before it schedules: with the state it last answered, which
     * only a stored receipt has. Answers the last one as it is scheduled, without it.
     */
    private fun store(vararg records: JSONObject): JSONObject {
        val receipts = records.map { JSONObject(it.toString()).put("reportedState", "unknown") }
        dispatch("write", JSONObject().put("records", JSONArray(receipts)))
        return records.lastOrNull() ?: JSONObject()
    }

    private fun starts(): Map<String, String> = outcomes(dispatch("starts").getJSONArray("data"))

    private fun outcomes(reports: JSONArray): Map<String, String> = (0 until reports.length()).associate { index ->
        val report = reports.getJSONObject(index)
        report.getString("id") to report.getString("outcome")
    }
}
