package io.github.eedaegon.liveupdate

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.SerializationHelper
import androidx.wear.ongoing.Status

/** From this API level a watch shows an Ongoing Activity. */
private const val ONGOING_ACTIVITY_API = 30
/** From this API level, Wear OS 7, a watch shows the promoted notification as a Live Update instead. */
private const val WEAR_LIVE_UPDATE_API = 37
/** Where the library keeps an Ongoing Activity among a notification's extras. */
internal const val WEAR_ONGOING_EXTRA = "android.wearable.ongoingactivities.EXTENSIONS"
private const val STATUS_PART = "status"
private const val TIME_PART = "time"

/**
 * Whether the adapter offers the Ongoing Activity: on a [watch] of API 30 to 36 whose application has the
 * [library], which is asked only there.
 */
internal fun offersWearOngoing(watch: Boolean, sdk: Int, library: () -> Boolean): Boolean =
    watch && sdk in ONGOING_ACTIVITY_API until WEAR_LIVE_UPDATE_API && library()

/**
 * Whether the application has `androidx.wear:wear-ongoing`, which the AAR does not bring. The library's class is
 * named in code, not by a string, so that a build that renames the library's classes still finds it.
 */
internal fun hasWearOngoingLibrary(): Boolean = try {
    WearOngoing.present()
} catch (_: LinkageError) {
    false
}

/**
 * Whether a shown notification has to be cancelled before another is posted in its place: when the new one
 * [carries] no Ongoing Activity and the shown one has one. A watch keeps the icon of an Ongoing Activity until
 * its notification is cancelled. [remembered] is what this process posted last in that place, null when it
 * posted nothing there; only then is Android's list asked, by [listed]: it knows a post some time after it.
 */
internal fun withdrawsOngoing(remembered: Boolean?, carries: Boolean, listed: () -> Boolean): Boolean =
    !carries && (remembered ?: listed())

/** [time] of the wall clock as the time since the device started, by the difference of the two now. */
internal fun elapsedAt(time: Long, wallNow: Long, elapsedNow: Long): Long = elapsedNow + (time - wallNow)

/**
 * The Ongoing Activity of a watch. This is the one class that names AndroidX classes. It is asked for the library
 * on a watch only, and puts nothing into a notification before the library was found.
 */
internal object WearOngoing {
    /** Names the library's class: without the library that is a `NoClassDefFoundError`. */
    fun present(): Boolean = OngoingActivity::class.java.name.isNotEmpty()

    /**
     * Puts into [notification], which is built and ongoing, the Ongoing Activity that shows what it shows: its
     * small icon, its content intent, its title, its category, and its text with its header clock. Without a
     * content intent there is nothing to open, and no Ongoing Activity.
     */
    fun apply(context: Context, notification: Notification) {
        val touch = notification.contentIntent ?: return
        // The library writes into a builder of AndroidX only: its data is copied from there.
        val carrier = NotificationCompat.Builder(context, notification.channelId)
        OngoingActivity.Builder(context, 0, carrier)
            .setStaticIcon(notification.smallIcon)
            .setTouchIntent(touch)
            .setTitle(notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            .setCategory(notification.category)
            .setStatus(status(notification))
            .build()
            .apply(context)
        SerializationHelper.copy(carrier.extras, notification.extras)
    }

    private fun status(notification: Notification): Status {
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val builder = Status.Builder().addPart(STATUS_PART, Status.TextPart(text))
        if (!notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) {
            return builder.addTemplate("#$STATUS_PART#").build()
        }
        // The header clock counts in the wall clock, the Ongoing Activity in the time since the device started.
        val zero = elapsedAt(notification.`when`, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        val countsDown = notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        val time = if (countsDown) Status.TimerPart(zero) else Status.StopwatchPart(zero)
        return builder.addPart(TIME_PART, time).addTemplate("#$STATUS_PART# #$TIME_PART#").build()
    }
}
