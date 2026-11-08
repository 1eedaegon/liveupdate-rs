package io.github.eedaegon.liveupdate

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveUpdateBridgeInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun prepareNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
            ).close()
        }
        waitForNotificationPermission()
        dispatch("write", JSONObject().put("records", JSONArray()))
        dispatch("cancel", JSONObject().put("id", ACTIVITY_ID))
    }

    @After
    fun removeNotification() {
        dispatch("cancel", JSONObject().put("id", ACTIVITY_ID))
        dispatch("write", JSONObject().put("records", JSONArray()))
    }

    @Test
    fun capabilitiesReflectTheRunningApi37Device() {
        val capabilities = dispatch("capabilities").getJSONObject("data")

        assertTrue(capabilities.getBoolean("notificationsEnabled"))
        assertEquals(Build.VERSION.SDK_INT >= 36, capabilities.getBoolean("supported"))
    }

    @Test
    fun durableRecordPostsObservesAndCancelsThroughThePublicBridge() {
        val record = record()
        dispatch("write", JSONObject().put("records", JSONArray().put(record)))
        assertEquals(ACTIVITY_ID, dispatch("read").getJSONArray("data")
            .getJSONObject(0).getString("id"))

        dispatch("post", JSONObject().put("record", record))
        val observed = waitForObservation(present = true).getJSONObject(0)
        assertEquals(ACTIVITY_ID, observed.getString("id"))
        assertEquals("active", observed.getString("state"))
        assertEquals("notApplicable", observed.getString("promotionState"))

        dispatch("cancel", JSONObject().put("id", ACTIVITY_ID))
        assertEquals(0, waitForObservation(present = false).length())
    }

    @Test
    fun nativeRustRunsTheCompleteLifecycleThroughJni() {
        val result = JSONObject(NativeRust.run(context))

        assertFalse(result.has("error"))
        assertTrue(result.getBoolean("notificationsEnabled"))
        assertTrue(result.getString("startedId").isNotBlank())
        assertEquals(1, result.getLong("updatedRevision"))
        assertEquals(1, result.getLong("listedRevision"))
        assertEquals("ended", result.getString("endedState"))
    }

    private fun waitForObservation(present: Boolean): JSONArray {
        repeat(40) {
            val observed = dispatch("observe").getJSONArray("data")
            if ((observed.length() > 0) == present) return observed
            Thread.sleep(50)
        }
        throw AssertionError("Notification observation did not become $present")
    }

    private fun waitForNotificationPermission() {
        repeat(40) {
            val capabilities = dispatch("capabilities").getJSONObject("data")
            if (capabilities.getBoolean("notificationsEnabled")) return
            Thread.sleep(50)
        }
        throw AssertionError("Notification permission did not become observable")
    }

    private fun dispatch(command: String, payload: JSONObject = JSONObject()): JSONObject {
        val response = JSONObject(LiveUpdateBridge.invoke(context, command, payload.toString()))
        assertEquals(response.toString(), "ok", response.getString("status"))
        return response
    }

    private fun record(): JSONObject {
        val snapshot = JSONObject()
            .put("revision", 0)
            .put("title", "Mochi's snack delivery")
            .put("status", "Preparing")
            .put("progress", 0.25)
        val origin = JSONObject()
            .put("activityKey", "delivery:instrumentation")
            .put("schemaVersion", 1)
            .put("template", "progress")
            .put("fallback", "notification")
            .put("visibility", "private")
            .put("snapshot", snapshot)
        return JSONObject()
            .put("id", ACTIVITY_ID)
            .put("origin", origin)
            .put("snapshot", snapshot)
            .put("state", "unknown")
            .put("promotionRequested", false)
            .put("promotionState", "notApplicable")
            .put("awaitingObservation", true)
            .put("postFailed", false)
    }

    private companion object {
        const val ACTIVITY_ID = "instrumentation-activity"
    }
}
