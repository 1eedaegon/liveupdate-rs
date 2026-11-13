package io.github.eedaegon.liveupdate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.eedaegon.liveupdate.Device.context
import io.github.eedaegon.liveupdate.Device.manager
import io.github.eedaegon.liveupdate.Device.record
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
}
