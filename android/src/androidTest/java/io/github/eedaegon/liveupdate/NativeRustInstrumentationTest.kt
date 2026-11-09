package io.github.eedaegon.liveupdate

import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.eedaegon.liveupdate.Device.context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The crate's Android path end to end: Rust code calls `LiveUpdate::android()` through JNI. */
@RunWith(AndroidJUnit4::class)
class NativeRustInstrumentationTest {
    @Before
    fun prepare() {
        Device.prepare()
        NativeRust.initialize(context)
    }

    @After
    fun clear() {
        NativeRust.initialize(context)
        Device.clear()
    }

    // LU-JNI-8, LU-JNI-2, LU-JNI-1
    @Test
    fun nativeRustRunsTheCompleteLifecycleOnARustThread() {
        val result = ok(NativeRust.run("lifecycle", ""))

        assertTrue(result.getBoolean("notificationsEnabled"))
        assertTrue(result.getString("startedId").isNotBlank())
        assertEquals(1, result.getLong("updatedRevision"))
        assertEquals(1, result.getLong("listedRevision"))
        assertEquals("ended", result.getString("endedState"))
    }

    // LU-JNI-2
    @Test
    fun nativeRustRunsTheLifecycleOnTheCallingJavaThread() {
        val result = ok(NativeRust.run("lifecycle-on-caller", ""))
        assertEquals("ended", result.getString("endedState"))
    }

    // LU-JNI-2, LU-CLIENT-1
    @Test
    fun manyRustThreadsShareOneClient() {
        val result = ok(NativeRust.run("threads", ""))

        assertEquals(List(8) { "Ended" }, result.getJSONArray("ended").let { ended ->
            List(ended.length()) { ended.getString(it) }
        })
        assertEquals(1, result.getInt("sharedIds"))
        // Android cancels asynchronously: the ended notifications go shortly after.
        Device.waitUntil("every notification is gone") { Device.manager.activeNotifications.isEmpty() }
    }

    // LU-JNI-3
    @Test
    fun manyCallsInARowKeepWorking() {
        val result = ok(NativeRust.run("calls", "100"))
        assertEquals(100, result.getInt("count"))
        println("liveupdate: state 3 s after 100 rapid updates: ${result.getString("stateAfter")}")
    }

    // LU-JNI-4, LU-START-7, LU-ADAPTER-3
    @Test
    fun textAndTimersCrossJniUnchanged() {
        val title = "🍡 \"Mochi\" 中文 \\ / \u0000 é"
        val endsAt = System.currentTimeMillis() + 600_000
        val snapshot = JSONObject()
            .put("revision", 9_007_199_254_740_990)
            .put("title", title)
            .put("status", "  next line")
            .put("timer", JSONObject().put("startedAt", endsAt - 60_000).put("endsAt", endsAt))
            .put("staleAt", 8_640_000_000_000_000)

        val started = ok(NativeRust.run("start", request("native:text", snapshot, "timer").toString()))

        assertEquals(Device.canonical(snapshot), Device.canonical(started.getJSONObject("snapshot")))
        val shown = Device.waitForShown(started.getString("id"))
        assertEquals(title, shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(endsAt, shown.`when`)
        val listed = okArray(NativeRust.run("list", "")).getJSONObject(0)
        assertEquals("active", listed.getString("state"))
        assertEquals(Device.canonical(snapshot), Device.canonical(listed.getJSONObject("snapshot")))
        assertEquals(started.getString("id"), ok(NativeRust.run("start", request("native:text", snapshot, "timer").toString())).getString("id"))
    }

    // LU-LIST-3, LU-UPDATE-6
    @Test
    fun aNotificationTheUserRemovesBecomesDismissed() {
        val started = ok(NativeRust.run("start", request("native:dismiss").toString()))
        val id = started.getString("id")
        Device.waitForShown(id)
        assertEquals("active", okArray(NativeRust.run("list", "")).getJSONObject(0).getString("state"))

        Device.manager.cancel(Device.TAG_PREFIX + id, Device.NOTIFICATION_ID)
        Device.waitForGone(id)

        assertEquals("dismissed", okArray(NativeRust.run("list", "")).getJSONObject(0).getString("state"))
        val update = JSONObject().put("id", id).put("snapshot", Device.snapshot(1))
        assertEquals("NOT_FOUND", errorCode(NativeRust.run("update", update.toString())))
        val end = JSONObject().put("id", id).put("policy", JSONObject().put("kind", "immediate"))
        assertEquals("ended", ok(NativeRust.run("end", end.toString())).getString("state"))
    }

    // LU-CAP-4, LU-FALLBACK-3, LU-API-2, LU-API-3
    @Test
    fun promotionIsRequestedWhereverAndroidAllowsIt() {
        val capabilities = ok(NativeRust.run("capabilities", ""))
        val canPromote = capabilities.getBoolean("supported") &&
            capabilities.getBoolean("notificationsEnabled") &&
            capabilities.getBoolean("promotionEnabled")
        assertEquals(Build.VERSION.SDK_INT >= 36, capabilities.getBoolean("supported"))

        val strict = request("native:promoted").put("fallback", "none")
        val outcome = JSONObject(NativeRust.run("start", strict.toString()))
        if (!canPromote) {
            assertEquals("UNSUPPORTED_FEATURE", outcome.getJSONObject("error").getString("code"))
            return
        }
        val started = outcome.getJSONObject("ok")
        assertTrue(started.getBoolean("promotionRequested"))
        Device.waitForShown(started.getString("id"))
        val listed = okArray(NativeRust.run("list", "")).getJSONObject(0)
        val flags = Device.shown(started.getString("id"))!!.notification.flags
        val promoted = flags and Notification.FLAG_PROMOTED_ONGOING != 0
        assertEquals(if (promoted) "promoted" else "notPromoted", listed.getString("promotionState"))
        assertEquals(if (promoted) 0 else 1, listed.getJSONArray("degradedFeatures").length())
        println("liveupdate: API ${Build.VERSION.SDK_INT} promoted=$promoted")
    }

    // LU-JNI-6
    @Test
    fun withoutNdkContextTheClientIsAnErrorAndLaterSucceeds() {
        NativeRust.release()

        assertEquals("NATIVE_ERROR", errorCode(NativeRust.run("new", "")))
        assertEquals("NATIVE_ERROR", errorCode(NativeRust.run("new-on-caller", "")))

        NativeRust.initialize(context)
        ok(NativeRust.run("new", ""))
    }

    // LU-JNI-5, LU-JNI-7
    @Test
    fun aContextWithoutAnApplicationIsUsedAndItsJavaExceptionsAreErrors() {
        val detached = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context? = null
        }
        NativeRust.initialize(detached)

        ok(NativeRust.run("new", ""))
        assertEquals("NATIVE_ERROR", errorCode(NativeRust.run("capabilities", "")))
        assertEquals("NATIVE_ERROR", errorCode(NativeRust.run("capabilities-on-caller", "")))
        assertFalse(JSONObject(NativeRust.run("list-on-caller", "")).has("panic"))
    }

    // LU-JNI-5
    @Test
    fun aContextThatThrowsMakesTheClientAnError() {
        val throwsOnApplication = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = throw IllegalStateException("no application")
        }
        val throwsOnClassLoader = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getClassLoader(): ClassLoader = throw IllegalStateException("no class loader")
        }
        for (broken in listOf(throwsOnApplication, throwsOnClassLoader)) {
            NativeRust.initialize(broken)
            assertEquals("NATIVE_ERROR", errorCode(NativeRust.run("new", "")))
            assertEquals("NATIVE_ERROR", errorCode(NativeRust.run("new-on-caller", "")))
        }
    }

    // LU-JNI-7
    @Test
    fun withoutTheAarClassesTheClientIsAnError() {
        val withoutAar = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getClassLoader(): ClassLoader = object : ClassLoader(null) {}
        }
        NativeRust.initialize(withoutAar)

        assertEquals("NATIVE_ERROR", errorCode(NativeRust.run("new", "")))
        NativeRust.initialize(context)
        ok(NativeRust.run("new", ""))
    }

    private fun request(
        key: String,
        snapshot: JSONObject = Device.snapshot(),
        template: String = "progress",
    ): JSONObject = JSONObject()
        .put("activityKey", key)
        .put("schemaVersion", 1)
        .put("template", template)
        .put("fallback", "notification")
        .put("visibility", "private")
        .put("snapshot", snapshot)

    private fun ok(raw: String): JSONObject {
        val outcome = JSONObject(raw)
        assertTrue(raw, outcome.has("ok"))
        return outcome.optJSONObject("ok") ?: JSONObject()
    }

    private fun okArray(raw: String): JSONArray {
        val outcome = JSONObject(raw)
        assertTrue(raw, outcome.has("ok"))
        return outcome.getJSONArray("ok")
    }

    private fun errorCode(raw: String): String {
        val outcome = JSONObject(raw)
        assertTrue(raw, outcome.has("error"))
        return outcome.getJSONObject("error").getString("code")
    }

    companion object {
        /** The Rust coverage counters of this process, which `tests/android/run.sh` pulls. */
        @JvmStatic
        @AfterClass
        fun writeCoverage() {
            val file = File(context.filesDir, "liveupdate.profraw")
            println("liveupdate: Rust coverage written=${NativeRust.writeCoverage(file.absolutePath)}")
        }
    }
}
