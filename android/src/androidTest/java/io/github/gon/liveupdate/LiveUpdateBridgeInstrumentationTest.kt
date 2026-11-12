package io.github.gon.liveupdate

import android.Manifest
import android.content.Context
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gon.liveupdate.Device.context
import io.github.gon.liveupdate.Device.dispatch
import io.github.gon.liveupdate.Device.failure
import io.github.gon.liveupdate.Device.record
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The JSON bridge that the Rust crate calls, against the device's real framework. */
@RunWith(AndroidJUnit4::class)
class LiveUpdateBridgeInstrumentationTest {
    @Before
    fun prepare() = Device.prepare()

    @After
    fun clear() = Device.clear()

    // LU-CAP-2, LU-API-2, LU-API-3, LU-V2-CAP-1
    @Test
    fun capabilitiesReflectTheRunningDevice() {
        val expected = InstrumentationRegistry.getArguments().getString("expectedSdk")
        if (expected != null) assertEquals(expected.toInt(), Build.VERSION.SDK_INT)
        val capabilities = dispatch("capabilities").getJSONObject("data")

        assertTrue(capabilities.getBoolean("notificationsEnabled"))
        assertEquals(Build.VERSION.SDK_INT >= 36, capabilities.getBoolean("supported"))
        val promotion = Build.VERSION.SDK_INT >= 36 && Device.manager.canPostPromotedNotifications()
        assertEquals(promotion, capabilities.getBoolean("promotionEnabled"))
        val presentation = capabilities.getJSONObject("presentation")
        assertTrue(presentation.getBoolean("standard"))
        assertTrue(presentation.getBoolean("bigText"))
        assertTrue(presentation.getBoolean("timer"))
        assertEquals(Build.VERSION.SDK_INT >= 36, presentation.getBoolean("progressStyle"))
        assertEquals(Build.VERSION.SDK_INT >= 37, presentation.getBoolean("metricStyle"))
        assertTrue(presentation.getBoolean("actions"))
        assertTrue(presentation.getBoolean("images"))
        assertTrue(presentation.getBoolean("events"))
        assertEquals(
            Build.VERSION.SDK_INT >= 37 && Build.MANUFACTURER.equals("samsung", ignoreCase = true),
            presentation.getBoolean("samsungNowBar"),
        )
    }

    // LU-ADAPTER-1, LU-ADAPTER-6, LU-ADAPTER-8, LU-ADAPTER-9
    @Test
    fun durableRecordPostsObservesAndCancelsThroughThePublicBridge() {
        val record = record(ACTIVITY_ID)
        dispatch("write", JSONObject().put("records", JSONArray().put(record)))
        assertEquals(ACTIVITY_ID, dispatch("read").getJSONArray("data")
            .getJSONObject(0).getString("id"))

        dispatch("post", JSONObject().put("record", record))
        Device.waitForShown(ACTIVITY_ID)
        val observed = Device.observed().getJSONObject(0)
        assertEquals(ACTIVITY_ID, observed.getString("id"))
        assertEquals("active", observed.getString("state"))
        assertEquals(false, observed.getBoolean("promotionRequested"))
        assertEquals("notApplicable", observed.getString("promotionState"))
        assertEquals(record.getJSONObject("snapshot").toString(), observed.getJSONObject("snapshot").toString())

        dispatch("cancel", JSONObject().put("id", ACTIVITY_ID))
        Device.waitForGone(ACTIVITY_ID)
        assertEquals(0, Device.observed().length())
        dispatch("cancel", JSONObject().put("id", "never-posted"))
    }

    // LU-ADAPTER-9, LU-BRIDGE-7
    @Test
    fun storageStartsEmptyAndRejectsCorruptContent() {
        assertEquals(0, dispatch("read").getJSONArray("data").length())

        val preferences = context.getSharedPreferences(Device.PREFERENCES, Context.MODE_PRIVATE)
        val tooMany = JSONArray().apply { repeat(161) { put(JSONObject()) } }.toString()
        for (corrupt in listOf("not json", "{}", tooMany, "[\"${"x".repeat(1_048_576)}\"]")) {
            preferences.edit().putString("records", corrupt).commit()
            failure("read", JSONObject(), "NATIVE_ERROR")
        }
        val full = JSONArray().apply { repeat(160) { put(JSONObject()) } }
        dispatch("write", JSONObject().put("records", full))
        assertEquals(160, dispatch("read").getJSONArray("data").length())
    }

    // LU-BRIDGE-6
    @Test
    fun theBridgeRejectsMalformedCommandsBeforeTheFramework() {
        failure("unknown", JSONObject(), "INVALID_INPUT")
        failure("read", JSONObject().put("extra", 1), "INVALID_INPUT")
        failure("post", JSONObject().put("record", JSONObject()), "INVALID_INPUT")
        failure("cancel", JSONObject().put("id", " "), "INVALID_INPUT")
        val response = JSONObject(LiveUpdateBridge.invoke(context, "read", "not json"))
        assertEquals("NATIVE_ERROR", response.getJSONObject("error").getString("code"))
        assertEquals(0, Device.manager.activeNotifications.size)
    }

    // LU-BRIDGE-6
    @Test
    fun theBridgeUsesTheApplicationContext() {
        val wrapper = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = context.applicationContext
        }
        val response = JSONObject(LiveUpdateBridge.invoke(wrapper, "capabilities", "{}"))
        assertEquals("ok", response.getString("status"))
    }

    // LU-AAR-1
    @Test
    fun theManifestMergesBothNotificationPermissions() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions!!.toSet()
        assertTrue(requested.toString(), Manifest.permission.POST_NOTIFICATIONS in requested)
        assertTrue(requested.toString(), Manifest.permission.POST_PROMOTED_NOTIFICATIONS in requested)

        val receivers = listOf(LiveUpdateActionReceiver::class.java, LiveUpdateDismissReceiver::class.java)
        for (receiver in receivers) {
            val info = context.packageManager.getReceiverInfo(ComponentName(context, receiver), 0)
            assertEquals(receiver.name, false, info.exported)
        }
    }

    private companion object {
        const val ACTIVITY_ID = "instrumentation-activity"
    }
}
