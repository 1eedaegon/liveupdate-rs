package io.github.eedaegon.liveupdate

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** What the instrumentation tests share: the device's state, the bridge and the record JSON. */
internal object Device {
    const val TAG_PREFIX = "liveupdate-rs:"
    const val NOTIFICATION_ID = 1
    const val CHANNEL_ID = "liveupdate-rs.v1"
    const val PREFERENCES = "io.github.1eedaegon.liveupdate"
    const val BIG_TEXT_STYLE = "android.app.Notification\$BigTextStyle"

    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext
    val manager = context.getSystemService(NotificationManager::class.java)

    /** Grants the notification permission and leaves no notification and no stored record. */
    fun prepare() {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        waitUntil("notifications are enabled") { manager.areNotificationsEnabled() }
        clear()
    }

    fun clear() {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        try {
            // Android posts asynchronously: what was still on its way at the first cancel is cancelled by a later one.
            waitUntil("no notification is shown") {
                manager.cancelAll()
                manager.activeNotifications.isEmpty()
            }
        } catch (error: AssertionError) {
            val left = manager.activeNotifications.joinToString { "${it.key} flags ${it.notification.flags}" }
            throw AssertionError("${error.message}: $left", error)
        }
    }

    /**
     * Runs [command] and waits for its end. Its output is read to the end before the pipe is closed: Android 8
     * ends the whole test run when a command writes to a pipe that is closed already.
     */
    fun shell(command: String) {
        shellOutput(command)
    }

    /** Runs [command] as [shell] does, and answers what it wrote. */
    fun shellOutput(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).use { String(it.readBytes()) }
    }

    /**
     * The windows of the alarms that Android holds for the test package, as `dumpsys alarm` prints them: `0` is
     * an exact alarm. An application cannot ask for its own alarms otherwise.
     */
    fun alarmWindows(): List<String> {
        val output = instrumentation.uiAutomation.executeShellCommand("dumpsys alarm")
        val dump = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readLines() }
        val alarm = Regex("""^\s*[A-Z_]+ #\d+: Alarm\{.* (\S+)\}\s*$""")
        val window = Regex("""\bwindow=(\S+)""")
        return dump.indices.filter { alarm.find(dump[it])?.groupValues?.get(1) == context.packageName }.map { index ->
            val lines = dump.subList(index + 1, dump.size).takeWhile { !alarm.matches(it) }
            checkNotNull(lines.firstNotNullOfOrNull { window.find(it)?.groupValues?.get(1) }) {
                "dumpsys alarm names no window for ${dump[index].trim()}"
            }
        }
    }

    /** One bridge call; the envelope's status must be `ok`. */
    fun dispatch(command: String, payload: JSONObject = JSONObject()): JSONObject {
        val response = JSONObject(LiveUpdateBridge.invoke(context, command, payload.toString()))
        assertEquals(response.toString(), "ok", response.getString("status"))
        return response
    }

    /** One bridge call that must fail with [code]. */
    fun failure(command: String, payload: JSONObject, code: String): JSONObject {
        val response = JSONObject(LiveUpdateBridge.invoke(context, command, payload.toString()))
        assertEquals(response.toString(), "error", response.getString("status"))
        assertEquals(response.toString(), code, response.getJSONObject("error").getString("code"))
        return response.getJSONObject("error")
    }

    fun shown(id: String): StatusBarNotification? =
        manager.activeNotifications.firstOrNull { it.tag == TAG_PREFIX + id && it.id == NOTIFICATION_ID }

    fun waitForShown(id: String, revision: Long = -1): Notification {
        var found: Notification? = null
        waitUntil("$id is shown at revision $revision") {
            found = shown(id)?.notification?.takeIf { revision < 0 || revisionOf(it) == revision }
            found != null
        }
        return found!!
    }

    fun waitForGone(id: String) = waitUntil("$id is gone") { shown(id) == null }

    /**
     * The class name of the notification's system style, null without one. Every Android version tells it;
     * `Notification.Builder.getStyle` is of API 28.
     */
    fun styleOf(notification: Notification): String? = notification.extras.getString(Notification.EXTRA_TEMPLATE)

    /** [intent] is immutable, where Android tells: `PendingIntent.isImmutable` is of API 31. */
    fun assertImmutable(intent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 31) assertTrue(intent.isImmutable)
    }

    fun observed(): JSONArray = dispatch("observe").getJSONArray("data")

    /**
     * Waits before the update of a notification: Android drops the updates of a package that posts more than
     * five notifications a second, by an average that a burst before this one has pulled down.
     */
    fun paceUpdate() = Thread.sleep(700)

    /** Polls for up to 30 seconds: the framework posts and cancels asynchronously. */
    fun waitUntil(what: String, condition: () -> Boolean) {
        repeat(600) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting until $what")
    }

    fun snapshot(revision: Long = 0, progress: Double? = 0.25): JSONObject = JSONObject()
        .put("revision", revision)
        .put("title", "Mochi's snack delivery")
        .put("status", "Preparing")
        .apply { if (progress != null) put("progress", progress) }

    fun timer(startedAt: Long, endsAt: Long): JSONObject =
        JSONObject().put("startedAt", startedAt).put("endsAt", endsAt)

    fun origin(snapshot: JSONObject, visibility: String = "private"): JSONObject = JSONObject()
        .put("activityKey", "delivery:instrumentation")
        .put("schemaVersion", 1)
        .put("template", if (snapshot.has("timer")) "timer" else "progress")
        .put("fallback", "notification")
        .put("visibility", visibility)
        .put("snapshot", snapshot)

    fun record(
        id: String,
        snapshot: JSONObject = snapshot(),
        visibility: String = "private",
        promotionRequested: Boolean = false,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("origin", origin(snapshot, visibility))
        .put("snapshot", snapshot)
        .put("state", "unknown")
        .put("promotionRequested", promotionRequested)
        .put("promotionState", if (promotionRequested) "unknown" else "notApplicable")
        .put("awaitingObservation", true)
        .put("postFailed", false)

    fun v2Snapshot(
        style: JSONObject,
        revision: Long = 0,
        actions: JSONArray = JSONArray(),
        shortCriticalText: String? = null,
        largeIcon: JSONObject? = null,
        samsungNowBar: JSONObject? = null,
        chronometer: JSONObject? = null,
    ): JSONObject = JSONObject()
        .put("revision", revision)
        .put("title", "Mochi's snack delivery")
        .put("status", "Preparing")
        .put("presentation", JSONObject()
            .put("style", style)
            .put("actions", actions)
            .apply {
                if (shortCriticalText != null) put("shortCriticalText", shortCriticalText)
                if (largeIcon != null) put("largeIcon", largeIcon)
                if (samsungNowBar != null) put("samsungNowBar", samsungNowBar)
                if (chronometer != null) put("chronometer", chronometer)
            })

    fun v2Record(
        id: String,
        template: String,
        snapshot: JSONObject,
        visibility: String = "private",
        promotionRequested: Boolean = true,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("origin", JSONObject()
            .put("activityKey", "delivery:instrumentation")
            .put("schemaVersion", 2)
            .put("template", template)
            .put("fallback", "notification")
            .put("visibility", visibility)
            .put("snapshot", snapshot))
        .put("snapshot", snapshot)
        .put("state", "unknown")
        .put("promotionRequested", promotionRequested)
        .put("promotionState", if (promotionRequested) "unknown" else "notApplicable")
        .put("awaitingObservation", true)
        .put("postFailed", false)

    fun resourceIcon(name: String) = JSONObject().put("kind", "resource").put("value", name)

    fun appFileIcon(path: String) = JSONObject().put("kind", "appFile").put("value", path)

    /** JSON text with every object's members in name order, to compare JSON from Rust and Kotlin. */
    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private fun revisionOf(notification: Notification): Long =
        JSONObject(notification.extras.getString("io.github.1eedaegon.liveupdate.record")!!)
            .getJSONObject("snapshot").getLong("revision")
}
