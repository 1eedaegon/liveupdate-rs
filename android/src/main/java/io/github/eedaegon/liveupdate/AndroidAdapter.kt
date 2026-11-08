package io.github.eedaegon.liveupdate

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

private const val CHANNEL_ID = "liveupdate-rs.v1"
private const val CHANNEL_NAME = "Live updates"
private const val PREFERENCES = "io.github.1eedaegon.liveupdate"
private const val RECORDS_KEY = "records"
private const val TAG_PREFIX = "liveupdate-rs:"
private const val RECORD_EXTRA = "io.github.1eedaegon.liveupdate.record"
private const val NOTIFICATION_ID = 1
private const val MAX_RECORDS = 160
private const val MAX_RECORD_BYTES = 1_048_576
private const val INVALID_MESSAGE = "Invalid live update request."
private const val NATIVE_MESSAGE = "The Android live update adapter failed."

internal class AndroidAdapter(context: Context) : ManagedAdapter {
    private val application = context.applicationContext
    private val manager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun capabilities(): JSONObject {
        ensureChannel()
        return JSONObject()
            .put("supported", Build.VERSION.SDK_INT >= 36)
            .put("notificationsEnabled", notificationsAllowed())
            .put("promotionEnabled", promotionAllowed())
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

    override fun observe(): JSONArray {
        val observations = JSONArray()
        for (entry in manager.activeNotifications) {
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
        val snapshot = record.optJSONObject("snapshot")
        if (!validId(id) || snapshot == null) invalid()
        val title = requiredText(snapshot, "title", 120)
        val status = requiredText(snapshot, "status", 240)
        val visibility = requestedVisibility(record)
        val requested = record.optBoolean("promotionRequested", false)
        val builder = builder()
            .setSmallIcon(R.drawable.liveupdate_status)
            .setContentTitle(title)
            .setContentText(status)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(visibility)
            .setShowWhen(false)
            .setExtras(Bundle().apply { putString(RECORD_EXTRA, record.toString()) })
        contentIntent()?.let(builder::setContentIntent)
        applyTemplate(builder, snapshot)
        if (Build.VERSION.SDK_INT >= 36 && requested) {
            builder.addExtras(Bundle().apply {
                putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true)
            })
        }
        manager.notify(TAG_PREFIX + id, NOTIFICATION_ID, builder.build())
    }

    override fun cancel(id: String) {
        if (!validId(id)) invalid()
        manager.cancel(TAG_PREFIX + id, NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notificationsAllowed(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 26) return true
        return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun promotionAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < 36 || !manager.canPostPromotedNotifications()) return false
        val importance = manager.getNotificationChannel(CHANNEL_ID)?.importance
            ?: NotificationManager.IMPORTANCE_LOW
        return importance > NotificationManager.IMPORTANCE_MIN
    }

    @Suppress("DEPRECATION")
    private fun builder(): Notification.Builder = if (Build.VERSION.SDK_INT >= 26) {
        Notification.Builder(application, CHANNEL_ID)
    } else {
        Notification.Builder(application).setPriority(Notification.PRIORITY_LOW)
    }

    private fun contentIntent(): PendingIntent? {
        val launch = application.packageManager.getLaunchIntentForPackage(application.packageName)
            ?: return null
        return PendingIntent.getActivity(
            application,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun applyTemplate(builder: Notification.Builder, snapshot: JSONObject) {
        val hasProgress = snapshot.has("progress") && !snapshot.isNull("progress")
        val timer = snapshot.optJSONObject("timer")
        if (hasProgress == (timer != null)) invalid()
        if (hasProgress) {
            val progress = snapshot.getDouble("progress")
            if (!progress.isFinite() || progress !in 0.0..1.0) invalid()
            builder.setProgress(1000, (progress * 1000).roundToInt(), false)
            return
        }
        val startedAt = timer?.getLong("startedAt") ?: invalid()
        val endsAt = timer.getLong("endsAt")
        if (startedAt < 0 || endsAt <= startedAt) invalid()
        builder.setWhen(endsAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
    }

    private fun requestedVisibility(record: JSONObject): Int {
        val origin = record.optJSONObject("origin") ?: invalid()
        return when (origin.optionalString("visibility")) {
            "public" -> Notification.VISIBILITY_PUBLIC
            "private" -> Notification.VISIBILITY_PRIVATE
            "secret" -> Notification.VISIBILITY_SECRET
            else -> invalid()
        }
    }

    private fun isStale(snapshot: JSONObject): Boolean =
        snapshot.has("staleAt") && !snapshot.isNull("staleAt") &&
            snapshot.getLong("staleAt") <= System.currentTimeMillis()

    @SuppressLint("InlinedApi")
    private fun promotionState(notification: Notification, requested: Boolean): String = when {
        !requested -> "notApplicable"
        Build.VERSION.SDK_INT >= 36 && notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0 -> "promoted"
        else -> "notPromoted"
    }

    private fun requiredText(snapshot: JSONObject, name: String, maximum: Int): String {
        val value = snapshot.optionalString(name) ?: invalid()
        if (value.isBlank() || value.codePointCount(0, value.length) > maximum) invalid()
        return value
    }

    private fun validId(id: String?): Boolean = id != null && id.isNotBlank() && id.length <= 256

    private fun invalid(): Nothing = throw AdapterFailure("INVALID_INPUT", INVALID_MESSAGE)

    private fun nativeFailure(): Nothing = throw AdapterFailure("NATIVE_ERROR", NATIVE_MESSAGE)
}
