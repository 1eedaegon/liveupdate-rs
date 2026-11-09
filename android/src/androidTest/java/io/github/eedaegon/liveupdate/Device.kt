package io.github.eedaegon.liveupdate

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals

/** What the instrumentation tests share: the device's state, the bridge and the record JSON. */
internal object Device {
    const val TAG_PREFIX = "liveupdate-rs:"
    const val NOTIFICATION_ID = 1
    const val CHANNEL_ID = "liveupdate-rs.v1"
    const val PREFERENCES = "io.github.1eedaegon.liveupdate"

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
        manager.cancelAll()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        waitUntil("no notification is shown") { manager.activeNotifications.isEmpty() }
    }

    fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
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

    fun observed(): JSONArray = dispatch("observe").getJSONArray("data")

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
