package io.github.eedaegon.liveupdate

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

private const val CHANNEL_ID = "liveupdate-rs.v1"
private const val CHANNEL_NAME = "Live updates"
private const val PREFERENCES = "io.github.1eedaegon.liveupdate"
private const val RECORDS_KEY = "records"
private const val TAG_PREFIX = "liveupdate-rs:"
private const val NOTIFICATION_ID = 1
private const val MAX_RECORDS = 160
private const val MAX_RECORD_BYTES = 1_048_576
private const val PROMOTION_API = 36
private const val INVALID_MESSAGE = "Invalid live update request."
private const val NATIVE_MESSAGE = "The Android live update adapter failed."

/**
 * Android framework calls for the Rust lifecycle.
 *
 * [sdk] and [manufacturer] describe the device behavior the adapter follows. They use the real
 * device values; tests override them to exercise platform- and vendor-specific paths.
 */
internal class AndroidAdapter(
    context: Context,
    private val sdk: Int = Build.VERSION.SDK_INT,
    private val manufacturer: String = Build.MANUFACTURER,
) : ManagedAdapter {
    private val application = context.applicationContext
    private val manager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun capabilities(): JSONObject {
        ensureChannel()
        return JSONObject()
            .put("supported", sdk >= PROMOTION_API)
            .put("notificationsEnabled", notificationsAllowed())
            .put("promotionEnabled", promotionAllowed())
            .put("presentation", JSONObject()
                .put("standard", true)
                .put("bigText", true)
                .put("timer", true)
                .put("progressStyle", sdk >= 36)
                .put("metricStyle", sdk >= 37)
                .put("actions", true)
                .put("images", true)
                .put("events", true)
                .put("samsungNowBar", sdk >= 37 && manufacturer.equals("samsung", ignoreCase = true)))
    }

    override fun read(): JSONArray {
        val raw = preferences.getString(RECORDS_KEY, "[]") ?: "[]"
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) nativeFailure()
        return JSONArray(raw).also { if (it.length() > MAX_RECORDS) nativeFailure() }
    }

    override fun write(records: JSONArray) {
        if (records.length() > MAX_RECORDS) invalid()
        val raw = records.toString()
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) invalid()
        if (!preferences.edit().putString(RECORDS_KEY, raw).commit()) nativeFailure()
    }

    override fun observe(): JSONArray = observe(manager.activeNotifications)

    /** The adapter's own notifications among [entries], Android's active notifications. */
    internal fun observe(entries: Array<StatusBarNotification>): JSONArray {
        val observations = JSONArray()
        for (entry in entries) {
            if (entry.id != NOTIFICATION_ID || entry.tag?.startsWith(TAG_PREFIX) != true) continue
            if (observations.length() >= MAX_RECORDS) nativeFailure()
            val raw = entry.notification.extras.getString(RECORD_EXTRA) ?: nativeFailure()
            val record = try {
                JSONObject(raw)
            } catch (_: Exception) {
                nativeFailure()
            }
            val id = record.optionalString("id") ?: nativeFailure()
            if (!validId(id) || entry.tag != TAG_PREFIX + id) nativeFailure()
            val snapshot = record.optJSONObject("snapshot") ?: nativeFailure()
            val requested = record.optBoolean("promotionRequested", false)
            val state = if (isStale(snapshot)) "stale" else "active"
            observations.put(
                JSONObject()
                    .put("id", id)
                    .put("snapshot", snapshot)
                    .put("state", state)
                    .put("promotionRequested", requested)
                    .put("promotionState", promotionState(entry.notification, requested)),
            )
        }
        return observations
    }

    @SuppressLint("InlinedApi")
    override fun post(record: JSONObject) {
        ensureChannel()
        if (!notificationsAllowed()) {
            throw AdapterFailure("PERMISSION_DENIED", "Notifications are disabled.")
        }
        val id = record.optionalString("id") ?: invalid()
        if (!validId(id)) invalid()
        val notification = NotificationRenderer(application, sdk, CHANNEL_ID, manufacturer).build(record)
        manager.notify(TAG_PREFIX + id, NOTIFICATION_ID, notification)
    }

    override fun cancel(id: String) {
        if (!validId(id)) invalid()
        manager.cancel(TAG_PREFIX + id, NOTIFICATION_ID)
    }

    override fun drainEvents(): JSONObject = EventStore(application).drain()

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notificationsAllowed(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun promotionAllowed(): Boolean {
        if (sdk < PROMOTION_API || Build.VERSION.SDK_INT < PROMOTION_API) return false
        if (!manager.canPostPromotedNotifications()) return false
        val importance = manager.getNotificationChannel(CHANNEL_ID)?.importance
            ?: NotificationManager.IMPORTANCE_LOW
        return importance > NotificationManager.IMPORTANCE_MIN
    }

    private fun isStale(snapshot: JSONObject): Boolean =
        snapshot.has("staleAt") && !snapshot.isNull("staleAt") &&
            snapshot.getLong("staleAt") <= System.currentTimeMillis()

    @SuppressLint("InlinedApi")
    private fun promotionState(notification: Notification, requested: Boolean): String = when {
        !requested -> "notApplicable"
        sdk >= PROMOTION_API && notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0 -> "promoted"
        else -> "notPromoted"
    }

    private fun validId(id: String?): Boolean = id != null && isValidContractText(id, 256)

    private fun invalid(): Nothing = throw AdapterFailure("INVALID_INPUT", INVALID_MESSAGE)

    private fun nativeFailure(): Nothing = throw AdapterFailure("NATIVE_ERROR", NATIVE_MESSAGE)
}
