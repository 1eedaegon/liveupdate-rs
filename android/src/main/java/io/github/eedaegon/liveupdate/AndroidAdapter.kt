package io.github.eedaegon.liveupdate

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val CHANNEL_ID = "liveupdate-rs.v1"
private const val CHANNEL_NAME = "Live updates"
private const val ALERT_CHANNEL_ID = "liveupdate-rs.alerts.v1"
private const val ALERT_CHANNEL_NAME = "Live update alerts"
private const val PREFERENCES = "io.github.1eedaegon.liveupdate"
private const val RECORDS_KEY = "records"
private const val TAG_PREFIX = "liveupdate-rs:"
private const val NOTIFICATION_ID = 1
private const val MAX_RECORDS = 160
private const val MAX_RECORD_BYTES = 1_048_576
private const val PROMOTION_API = 36
/** How long Android may take to list a post. */
private const val LISTING_GRACE = 10_000L
private const val OLD_TIME_API = 35
private const val MAX_TIME_AGE = 14 * 24 * 60 * 60 * 1000L
/** What a post may take from the adapter's check to Android's. */
internal const val POST_MARGIN = 60_000L
/** The margin for a clock that the renderer adds on its own: the adapter's check of the post comes after it. */
internal const val FALLBACK_CLOCK_MARGIN = 2 * POST_MARGIN
private const val INVALID_MESSAGE = "Invalid live update request."
private const val OLD_CLOCK_MESSAGE = "The clock is more than 14 days old: Android would not post it."
private const val NATIVE_MESSAGE = "The Android live update adapter failed."

/**
 * Whether the notification this process posted last under a tag and an id carried an Ongoing Activity, where the
 * adapter offers one. It is forgotten when the notification is cancelled.
 */
private val postedOngoing = ConcurrentHashMap<String, Boolean>()

/**
 * Whether Android posts a notification whose header clock is [age] milliseconds old when it is posted. From API
 * 35 it ignores one of more than 14 days, without an error; [margin] is what the post may take to reach it.
 */
internal fun postsClock(sdk: Int, age: Long, margin: Long): Boolean =
    sdk < OLD_TIME_API || age <= MAX_TIME_AGE - margin

/**
 * Android framework calls for the Rust lifecycle.
 *
 * [sdk] and [manufacturer] describe the device behavior the adapter follows. They use the real
 * device values; tests override them to exercise platform- and vendor-specific paths, and
 * [alertChannelId] to block the alerting channel as a user does. [exactAlarms] says whether an exact alarm may
 * be set, where Android is not to be asked, [listingGrace] how long a post may stay unlisted, and [wearOngoing]
 * whether a watch's Ongoing Activity is offered, where the device is not to be asked.
 */
internal class AndroidAdapter(
    context: Context,
    private val sdk: Int = Build.VERSION.SDK_INT,
    private val manufacturer: String = Build.MANUFACTURER,
    private val alertChannelId: String = ALERT_CHANNEL_ID,
    exactAlarms: Boolean? = null,
    listingGrace: Long = LISTING_GRACE,
    wearOngoing: Boolean? = null,
) : ManagedAdapter {
    private val application = context.applicationContext
    private val manager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val scheduled = ScheduledStarts(application, this, preferences, exactAlarms, listingGrace)
    private val wearOngoing = wearOngoing ?: offersWearOngoing(
        application.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH),
        sdk,
        ::hasWearOngoingLibrary,
    )

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
                .put("samsungNowBar", sdk >= 37 && manufacturer.equals("samsung", ignoreCase = true))
                .put("chronometer", true)
                .put("links", true)
                .put("styleChange", true)
                .put("delayedEnd", true)
                .put("foregroundPlacement", true)
                .put("alert", true)
                .put("publicVersion", true)
                .put("branding", true)
                .put("scheduledStart", true)
                .put("exactStart", scheduled.exact())
                .put("wearOngoingActivity", wearOngoing)
                .put("actionTargets", true))
    }

    override fun read(): JSONArray {
        val raw = preferences.getString(RECORDS_KEY, "[]") ?: "[]"
        if (exceedsUtf8(raw, MAX_RECORD_BYTES)) nativeFailure()
        return JSONArray(raw).also { if (it.length() > MAX_RECORDS) nativeFailure() }
    }

    override fun write(records: JSONArray) {
        if (records.length() > MAX_RECORDS) invalid()
        val raw = records.toString()
        if (exceedsUtf8(raw, MAX_RECORD_BYTES)) invalid()
        if (!preferences.edit().putString(RECORDS_KEY, raw).commit()) nativeFailure()
    }

    override fun observe(): JSONArray = observe(manager.activeNotifications)

    /** The adapter's own notifications among [entries], Android's active notifications. */
    internal fun observe(entries: Array<StatusBarNotification>): JSONArray {
        val observations = JSONArray()
        for (entry in entries) {
            val tagged = entry.id == NOTIFICATION_ID && entry.tag?.startsWith(TAG_PREFIX) == true
            // Another notification of the application may use the foreground id; the adapter's carries a record.
            if (!tagged && !isForegroundEntry(entry)) continue
            val raw = entry.notification.extras.getString(RECORD_EXTRA) ?: nativeFailure()
            val record = try {
                JSONObject(raw)
            } catch (_: Exception) {
                nativeFailure()
            }
            val id = record.optionalString("id") ?: nativeFailure()
            if (!validId(id) || (tagged && entry.tag != TAG_PREFIX + id)) nativeFailure()
            // The last content of an ended activity is no longer the lifecycle's to reconcile.
            if (record.optionalString("state") == "ended") continue
            if (observations.length() >= MAX_RECORDS) nativeFailure()
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
        val notification = build(record)
        val foreground = isForegroundPlaced(record)
        val removes = !record.isNull("dismissAt") && record.getLong("dismissAt") <= System.currentTimeMillis()
        if (!removes) refuseOldTime(notification)
        if (!record.isNull("dismissAt")) {
            // The last content of an ended activity has the activity's own tag, also after a foreground
            // placement: the service's notification goes with the service. A time that has passed removes it.
            if (foreground) cancelForeground(id)
            if (removes) {
                manager.cancel(TAG_PREFIX + id, NOTIFICATION_ID)
                postedOngoing.remove(place(TAG_PREFIX + id, NOTIFICATION_ID))
            } else {
                show(TAG_PREFIX + id, NOTIFICATION_ID, notification)
            }
            return
        }
        when {
            !foreground -> show(TAG_PREFIX + id, NOTIFICATION_ID, notification)
            // A held activity is updated through its service, never past it.
            LiveUpdateForeground.update(id, notification) -> Unit
            else -> show(null, LiveUpdateForeground.NOTIFICATION_ID, notification)
        }
    }

    /**
     * Posts [notification] under [tag] and [id]. A watch shows the icon of an Ongoing Activity until its
     * notification is cancelled, also after an update that carries none. So where the adapter offers the
     * Ongoing Activity and [notification] has none, a shown notification that has one is cancelled first: a
     * presentation turned it off, or the activity ended. What a service holds cannot be cancelled and is not
     * posted here.
     */
    private fun show(tag: String?, id: Int, notification: Notification) {
        if (wearOngoing) {
            val carries = notification.extras.containsKey(WEAR_ONGOING_EXTRA)
            val remembered = postedOngoing.put(place(tag, id), carries)
            if (withdrawsOngoing(remembered, carries) { listsOngoing(tag, id) }) manager.cancel(tag, id)
        }
        manager.notify(tag, id, notification)
    }

    private fun place(tag: String?, id: Int) = "$tag|$id"

    /** Whether the notification that Android lists under [tag] and [id] carries an Ongoing Activity. */
    private fun listsOngoing(tag: String?, id: Int): Boolean = try {
        manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id }
            ?.notification?.extras?.containsKey(WEAR_ONGOING_EXTRA) == true
    } catch (_: Exception) {
        // A list that cannot be read keeps no post back.
        false
    }

    override fun cancel(id: String) {
        if (!validId(id)) invalid()
        // An activity that ended before its time has no alarm left.
        scheduled.rearm { manager.cancel(TAG_PREFIX + id, NOTIFICATION_ID) }
        postedOngoing.remove(place(TAG_PREFIX + id, NOTIFICATION_ID))
        cancelForeground(id)
    }

    override fun schedule(record: JSONObject) {
        ensureChannel()
        if (!notificationsAllowed()) {
            throw AdapterFailure("PERMISSION_DENIED", "Notifications are disabled.")
        }
        val id = record.optionalString("id") ?: invalid()
        if (!validId(id)) invalid()
        // What a post would refuse at the time is refused now, also a clock that is too old by then: the
        // notification is built as it will be at its time.
        val startAt = record.optJSONObject("origin")?.optLong("startAt") ?: 0
        val postedAt = maxOf(System.currentTimeMillis(), startAt)
        val notification = build(record, postedAt)
        if (record.optionalString("state") != "scheduled") invalid()
        refuseOldTime(notification, postedAt)
        // The alarm is set for the stored receipts, which the lifecycle writes before it schedules.
        if (stored(id)?.optionalString("state") != "scheduled") invalid()
        scheduled.take(id)
    }

    override fun starts(): JSONArray = scheduled.report()

    /** Begins the scheduled receipts whose time has come: for the alarm, and for a restart of the device. */
    internal fun startDue() = scheduled.run()

    /** The ids of the activities whose tagged notification Android lists, from one question to Android. */
    internal fun listed(): Set<String> = manager.activeNotifications.mapNotNullTo(mutableSetOf()) { entry ->
        entry.tag?.takeIf { entry.id == NOTIFICATION_ID && it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)
    }

    /** Removes the notification of [id] under the foreground id, if it is this activity's. */
    private fun cancelForeground(id: String) {
        // A service leaves the foreground with it: Android ignores the cancel of a notification that a service
        // holds.
        LiveUpdateForeground.release(id)
        val shown = manager.activeNotifications
            .firstOrNull { it.tag == null && it.id == LiveUpdateForeground.NOTIFICATION_ID }
        // The foreground id is this activity's when its notification is shown there, or when nothing is shown
        // yet and its receipt has the placement: Android posts asynchronously.
        val ours = if (shown != null) recordIdOf(shown) == id else stored(id)?.let(::isForegroundPlaced) == true
        if (!ours) return
        manager.cancel(LiveUpdateForeground.NOTIFICATION_ID)
        postedOngoing.remove(place(null, LiveUpdateForeground.NOTIFICATION_ID))
    }

    /**
     * The notification of a stored receipt as it is posted at [postedAt], on the alerting channel when its
     * revision alerts and the user allows.
     */
    internal fun build(record: JSONObject, postedAt: Long = System.currentTimeMillis()): Notification =
        renderer().build(record, alertChannel(record), postedAt)

    private fun renderer(): NotificationRenderer {
        val ongoing: ((Context, Notification) -> Unit)? = if (wearOngoing) WearOngoing::apply else null
        return NotificationRenderer(application, sdk, CHANNEL_ID, manufacturer, ongoing)
    }

    /**
     * The id and the notification of the live activity with the foreground placement, for a service of the
     * application to hold; null without one. It is built for the quiet channel: a service that attaches does not
     * alert.
     */
    internal fun foregroundNotification(): Pair<String, Notification>? {
        ensureChannel()
        val records = read()
        val record = (0 until records.length()).map(records::getJSONObject).firstOrNull { record ->
            isForegroundPlaced(record) && record.optionalString("state") !in setOf("ended", "dismissed")
        } ?: return null
        val id = record.optionalString("id") ?: return null
        val notification = renderer().build(posted(record))
        refuseOldTime(notification)
        return id to notification
    }

    /**
     * A stored receipt as the lifecycle posts it: without the state it last answered, which only the stored
     * receipt has and no posted record.
     */
    internal fun posted(receipt: JSONObject): JSONObject =
        JSONObject(receipt.toString()).apply { remove("reportedState") }

    /**
     * Refuses [notification] when Android would ignore it. From API 35 Android does not post a notification whose
     * time the application set more than 14 days before the device's clock, and tells the application nothing.
     * The adapter sets the time of a header clock only: any other notification has the time of its post.
     * [postedAt] is when the notification is posted, which is later for a scheduled start.
     */
    private fun refuseOldTime(notification: Notification, postedAt: Long = System.currentTimeMillis()) {
        if (!notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) return
        if (!postsClock(sdk, postedAt - notification.`when`, POST_MARGIN)) {
            throw AdapterFailure("INVALID_INPUT", OLD_CLOCK_MESSAGE)
        }
    }

    /** Whether a receipt of the activity [id] is stored. */
    internal fun knows(id: String): Boolean = stored(id) != null

    /** Whether the stored receipt of [id] has the action [actionId] and says that it requires authentication. */
    internal fun requiresAuthentication(id: String, actionId: String): Boolean =
        requiresAuthentication(stored(id), actionId)

    /** The stored receipt of [id], null without one: one read of the receipts for several questions. */
    internal fun receipt(id: String): JSONObject? = stored(id)

    /** Whether [receipt] has the action [actionId] and says that it requires authentication. */
    internal fun requiresAuthentication(receipt: JSONObject?, actionId: String): Boolean {
        val actions = receipt?.optJSONObject("snapshot")?.optJSONObject("presentation")?.optJSONArray("actions")
            ?: return false
        return (0 until actions.length()).mapNotNull(actions::optJSONObject)
            .any { it.optionalString("id") == actionId && it.opt("authenticationRequired") == true }
    }

    private fun stored(id: String): JSONObject? {
        val records = read()
        return (0 until records.length()).map(records::getJSONObject)
            .firstOrNull { it.optionalString("id") == id }
    }

    private fun isForegroundPlaced(record: JSONObject): Boolean =
        record.optJSONObject("origin")?.optionalString("placement") == "foreground"

    private fun isForegroundEntry(entry: StatusBarNotification): Boolean =
        entry.tag == null && entry.id == LiveUpdateForeground.NOTIFICATION_ID &&
            entry.notification.extras.getString(RECORD_EXTRA) != null

    private fun recordIdOf(entry: StatusBarNotification): String? = try {
        entry.notification.extras.getString(RECORD_EXTRA)?.let { JSONObject(it).optionalString("id") }
    } catch (_: Exception) {
        null
    }

    /** The alerting channel for a revision that alerts, unless the user blocked it; its posts then stay quiet. */
    private fun alertChannel(record: JSONObject): String? {
        val presentation = record.optJSONObject("snapshot")?.optJSONObject("presentation")
        if (presentation?.opt("alert") != true || !record.isNull("dismissAt")) return null
        manager.createNotificationChannel(
            NotificationChannel(alertChannelId, ALERT_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
        )
        val importance = manager.getNotificationChannel(alertChannelId)?.importance
        return alertChannelId.takeIf { importance != NotificationManager.IMPORTANCE_NONE }
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
