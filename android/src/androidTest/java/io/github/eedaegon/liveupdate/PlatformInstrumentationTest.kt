package io.github.eedaegon.liveupdate

import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.eedaegon.liveupdate.Device.context
import io.github.eedaegon.liveupdate.Device.dispatch
import io.github.eedaegon.liveupdate.Device.manager
import io.github.eedaegon.liveupdate.Device.v2Record
import io.github.eedaegon.liveupdate.Device.v2Snapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What Android's guides name beside a Live Update: off a paired watch, the settings of promotion, the channels' names. */
@RunWith(AndroidJUnit4::class)
class PlatformInstrumentationTest {
    private val adapter = AndroidAdapter(context)

    @Before
    fun prepare() = Device.prepare()

    @After
    fun clear() = Device.clear()

    private fun record(id: String, localOnly: Boolean?) = v2Record(
        id,
        "standard",
        v2Snapshot(JSONObject().put("kind", "standard")).apply {
            if (localOnly != null) getJSONObject("presentation").put("localOnly", localOnly)
        },
    )

    // LU-PLAT-1
    @Test
    fun anActivityKeptOffTheWatchIsLocalOnly() {
        assertTrue(adapter.capabilities().getJSONObject("presentation").getBoolean("localOnly"))
        adapter.post(record("kept", localOnly = true))
        adapter.post(record("shared", localOnly = null))
        val kept = Device.waitForShown("kept")
        val shared = Device.waitForShown("shared")
        assertTrue(kept.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertEquals(0, shared.flags and Notification.FLAG_LOCAL_ONLY)
    }

    // LU-PLAT-2
    @Test
    fun theSettingsOfPromotionOpen() {
        val answer = dispatch("settings")
        assertEquals(answer.toString(), "ok", answer.getString("status"))
        try {
            Device.waitUntil("a settings screen is in front") {
                Device.shellOutput("dumpsys activity activities").lineSequence()
                    .firstOrNull { "topResumedActivity" in it || "mResumedActivity" in it }
                    ?.contains("settings", ignoreCase = true) == true
            }
        } finally {
            Device.shell("input keyevent KEYCODE_BACK")
        }
    }

    // LU-PLAT-3
    @Test
    fun theChannelsAreNamedByTheApplicationsStrings() {
        manager.deleteNotificationChannel(Device.CHANNEL_ID)
        adapter.post(record("named", localOnly = null))
        Device.waitForShown("named")
        val channel = checkNotNull(manager.getNotificationChannel(Device.CHANNEL_ID))
        assertEquals(context.getString(R.string.liveupdate_channel_name), channel.name)
        assertEquals("Test rides", channel.name)
        assertEquals("What the tests post", channel.description)
    }
}
