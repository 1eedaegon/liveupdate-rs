package io.github.eedaegon.liveupdate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.eedaegon.liveupdate.Device.context
import io.github.eedaegon.liveupdate.Device.manager
import io.github.eedaegon.liveupdate.Device.record
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The adapter without the notification permission. Revoking a runtime permission kills the app's
 * process, so tests/android/run.sh revokes it before a run of this class alone, with the argument
 * `notifications=denied`; other runs skip it.
 */
@RunWith(AndroidJUnit4::class)
class PermissionInstrumentationTest {
    @Before
    fun requireTheRevokedPermission() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("notifications") == "denied")
        manager.cancelAll()
    }

    // LU-ADAPTER-7, LU-CAP-2
    @Test
    fun withoutTheNotificationPermissionPostsAreDenied() {
        assertFalse(manager.areNotificationsEnabled())
        val adapter = AndroidAdapter(context)
        assertFalse(adapter.capabilities().getBoolean("notificationsEnabled"))

        val failure = assertThrows(AdapterFailure::class.java) { adapter.post(record("denied")) }

        assertEquals("PERMISSION_DENIED", failure.code)
        val response = JSONObject(LiveUpdateBridge.invoke(
            context,
            "post",
            JSONObject().put("record", record("denied")).toString(),
        ))
        assertEquals("PERMISSION_DENIED", response.getJSONObject("error").getString("code"))
        assertEquals(0, manager.activeNotifications.size)
    }

    // LU-SCHED-9, LU-SCHED-11
    @Test
    fun withoutTheNotificationPermissionNothingIsScheduledAndADueStartIsGone() {
        val adapter = AndroidAdapter(context)
        val due = Device.v2Record("due", "standard", Device.v2Snapshot(JSONObject().put("kind", "standard")))
            .put("state", "scheduled")
            .put("awaitingObservation", false)
            .apply { getJSONObject("origin").put("startAt", System.currentTimeMillis() - 1_000) }
        adapter.write(JSONArray().put(due))

        val failure = assertThrows(AdapterFailure::class.java) { adapter.schedule(due) }

        assertEquals("PERMISSION_DENIED", failure.code)
        // Its time has come and it cannot be posted: gone, and not tried again.
        val reports = adapter.starts()
        assertEquals(1, reports.length())
        assertEquals("due", reports.getJSONObject(0).getString("id"))
        assertEquals("gone", reports.getJSONObject(0).getString("outcome"))
        assertEquals(1, adapter.starts().length())
        assertEquals(0, manager.activeNotifications.size)
        adapter.write(JSONArray())
    }
}
