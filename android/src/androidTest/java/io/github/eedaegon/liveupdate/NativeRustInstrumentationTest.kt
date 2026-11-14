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
import java.time.Instant

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

    // LU-FGS-4, LU-STATE-4
    @Test
    fun aServiceAttachesToAnActivityThatRustStartedAndStored() {
        val request = JSONObject()
            .put("activityKey", "native:held")
            .put("schemaVersion", 2)
            .put("template", "standard")
            .put("fallback", "notification")
            .put("visibility", "private")
            .put("snapshot", Device.v2Snapshot(JSONObject().put("kind", "standard")))
            .put("placement", "foreground")
        fun held() = Device.manager.activeNotifications
            .firstOrNull { it.tag == null && it.id == LiveUpdateForeground.NOTIFICATION_ID }

        val started = ok(NativeRust.run("start", request.toString()))
        Device.waitUntil("the activity that Rust started is shown") { held() != null }
        // The receipt that Rust stored has the state it last answered, which a posted record never has.
        assertEquals(started.getString("id"), AndroidAdapter(context).foregroundNotification()?.first)

        Device.paceUpdate()
        ForegroundProbeService.attach(context)
        try {
            Device.waitUntil("the service holds the activity") {
                held()?.let { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 } == true
            }
        } finally {
            val end = JSONObject().put("id", started.getString("id")).put("policy", JSONObject().put("kind", "immediate"))
            ok(NativeRust.run("end", end.toString()))
            val placeholder = Notification.Builder(context, Device.CHANNEL_ID)
                .setSmallIcon(R.drawable.liveupdate_status).build()
            ForegroundProbeService.stop(LiveUpdateForeground.NOTIFICATION_ID, placeholder)
        }
    }

    // LU-SCHED-14
    @Test
    fun aScheduledStartFromRustBeginsAtItsTimeAndIsAnsweredOnceEach() {
        val firstStartAt = System.currentTimeMillis() + 5_000
        val request = JSONObject()
            .put("activityKey", "native:scheduled")
            .put("schemaVersion", 2)
            .put("template", "standard")
            .put("fallback", "notification")
            .put("visibility", "private")
            .put("snapshot", Device.v2Snapshot(JSONObject().put("kind", "standard")))
            .put("startAt", firstStartAt)
        fun answered(): List<String> = okArray(NativeRust.run("stateChanges", "")).let { changes ->
            List(changes.length()) { changes.getJSONObject(it).getString("state") }
        }

        // A start and nothing after it: the alarm alone begins the activity.
        val alone = JSONObject(request.toString()).put("activityKey", "native:alone")
        val left = ok(NativeRust.run("start", alone.toString()))
        Device.waitForShown(left.getString("id"))
        assertTrue(System.currentTimeMillis() >= firstStartAt)
        assertEquals(listOf("active"), answered())

        request.put("startAt", System.currentTimeMillis() + 5_000)
        val startAt = request.getLong("startAt")
        val started = ok(NativeRust.run("start", request.toString()))

        assertEquals("scheduled", started.getString("state"))
        assertEquals(startAt, started.getLong("startAt"))
        assertEquals(listOf("scheduled"), answered())
        assertEquals(null, Device.shown(started.getString("id")))
        assertEquals("CONFLICT", errorCode(NativeRust.run("update", JSONObject()
            .put("id", started.getString("id"))
            .put("snapshot", Device.v2Snapshot(JSONObject().put("kind", "standard"), revision = 1)).toString())))
        // The alarm posts it: Rust is not asked until Android shows it.
        Device.waitForShown(started.getString("id"))
        assertTrue(System.currentTimeMillis() >= startAt)
        assertEquals(listOf("active"), answered())
        assertTrue(answered().isEmpty())
        assertEquals(started.getString("id"), ok(NativeRust.run("start", request.toString())).getString("id"))

        // A time that has passed begins at once: the first record is that of a post, never `scheduled`, and
        // `scheduled` is not answered for it.
        val passed = JSONObject(request.toString())
            .put("activityKey", "native:passed")
            .put("startAt", System.currentTimeMillis() - 1_000)
        val begun = ok(NativeRust.run("start", passed.toString()))
        assertTrue(begun.getString("state"), begun.getString("state") in setOf("unknown", "active"))
        Device.waitForShown(begun.getString("id"))
        assertEquals(listOf("active"), answered())
    }

    // LU-V2-JNI-1, LU-V2-EVENT-4
    @Test
    fun richPresentationAndActionEventsCrossRustJniUnchanged() {
        val action = JSONObject()
            .put("id", "confirm")
            .put("title", "확인")
        val style = JSONObject().put("kind", "progress").put("value", JSONObject()
            .put("progress", 75)
            .put("styledByProgress", true)
            .put("segments", JSONArray()
                .put(JSONObject().put("length", 50).put("id", 1).put("semanticStyle", "safe"))
                .put(JSONObject().put("length", 50).put("id", 2).put("semanticStyle", "caution")))
            .put("points", JSONArray().put(JSONObject().put("position", 50).put("id", 10))))
        val snapshot = Device.v2Snapshot(
            style,
            actions = JSONArray().put(action),
            shortCriticalText = "75%",
        )
        val request = JSONObject()
            .put("activityKey", "native:rich")
            .put("schemaVersion", 2)
            .put("template", "progress")
            .put("fallback", "notification")
            .put("visibility", "private")
            .put("snapshot", snapshot)

        val started = ok(NativeRust.run("start", request.toString()))
        assertEquals(Device.canonical(snapshot), Device.canonical(started.getJSONObject("snapshot")))
        val notification = Device.waitForShown(started.getString("id"))
        notification.actions.single().actionIntent.send()
        Device.waitUntil("the Rust action event is queued") { EventStore(context).size() == 1 }

        val batch = ok(NativeRust.run("events", ""))
        val event = batch.getJSONArray("events").getJSONObject(0)
        assertEquals(started.getString("id"), event.getString("activityId"))
        assertEquals("confirm", event.getJSONObject("kind").getString("actionId"))
        assertEquals(0, ok(NativeRust.run("events", "")).getJSONArray("events").length())
    }

    // LU-V2-METRIC-3
    @Test
    fun stopwatchAndCountdownMetricsCrossRustJniInTheAdaptersNames() {
        assertTrue(Build.VERSION.SDK_INT >= 37)
        val startedAt = System.currentTimeMillis() - 60_000
        val endsAt = startedAt + 660_000
        fun snapshot(revision: Long, started: String, ends: String, shift: Long = 0) = Device.v2Snapshot(
            JSONObject().put("kind", "metric").put("value", JSONObject().put("metrics", JSONArray()
                .put(timeMetric("Elapsed", "stopwatch", started, startedAt + shift))
                .put(timeMetric("Left", "countdown", ends, endsAt + shift)))),
            revision,
        )
        val request = JSONObject()
            .put("activityKey", "native:metric-times")
            .put("schemaVersion", 2)
            .put("template", "metric")
            .put("fallback", "notification")
            .put("visibility", "private")
            .put("snapshot", snapshot(0, "startedAt", "endsAt"))

        val started = ok(NativeRust.run("start", request.toString()))
        val id = started.getString("id")
        assertEquals(styleOf(request.getJSONObject("snapshot")), styleOf(started.getJSONObject("snapshot")))
        assertMetricTimes(Device.waitForShown(id, 0), startedAt, endsAt)

        // The names that versions up to 0.3.0 wrote are read, and posted in the adapter's names.
        Device.paceUpdate()
        val earlier = JSONObject().put("id", id).put("snapshot", snapshot(1, "started_at", "ends_at", 1_000))
        val updated = ok(NativeRust.run("update", earlier.toString()))
        assertEquals(
            styleOf(snapshot(1, "startedAt", "endsAt", 1_000)),
            styleOf(updated.getJSONObject("snapshot")),
        )
        assertMetricTimes(Device.waitForShown(id, 1), startedAt + 1_000, endsAt + 1_000)
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

    private fun timeMetric(label: String, kind: String, member: String, time: Long): JSONObject = JSONObject()
        .put("label", label)
        .put("value", JSONObject().put("kind", kind).put("value", JSONObject().put(member, time)))

    /** The style of a snapshot in a comparable form: Rust leaves out an empty list of actions. */
    private fun styleOf(snapshot: JSONObject): String =
        Device.canonical(snapshot.getJSONObject("presentation").getJSONObject("style"))

    private fun assertMetricTimes(notification: Notification, startedAt: Long, endsAt: Long) {
        val style = Notification.Builder.recoverBuilder(context, notification).style as Notification.MetricStyle
        val stopwatch = style.metrics[0].value as Notification.Metric.TimeDifference
        assertTrue(stopwatch.isStopwatch)
        assertEquals(Instant.ofEpochMilli(startedAt), stopwatch.zeroTime)
        val countdown = style.metrics[1].value as Notification.Metric.TimeDifference
        assertTrue(countdown.isTimer)
        assertEquals(Instant.ofEpochMilli(endsAt), countdown.zeroTime)
    }

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
