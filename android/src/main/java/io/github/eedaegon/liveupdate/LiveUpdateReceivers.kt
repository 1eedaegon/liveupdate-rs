package io.github.eedaegon.liveupdate

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

internal const val LIVE_UPDATE_ACTION = "io.github.1eedaegon.liveupdate.ACTION"
internal const val LIVE_UPDATE_DISMISSED = "io.github.1eedaegon.liveupdate.DISMISSED"
internal const val ACTIVITY_ID_EXTRA = "activityId"
internal const val ACTION_ID_EXTRA = "actionId"
internal const val AUTHENTICATION_EXTRA = "authenticationRequired"

/** From this API level Android asks for the unlock of an action that requires authentication. */
private const val AUTHENTICATION_API = 31

/**
 * Whether the tap of an action is dropped. Below API 31 Android sends the broadcast of a locked device at once,
 * so the tap of an action that requires authentication is not taken there while the device is locked. Each
 * question is asked only when the answer depends on it.
 */
internal fun withholdsAction(sdk: Int, authenticationRequired: () -> Boolean, deviceLocked: () -> Boolean): Boolean =
    sdk < AUTHENTICATION_API && authenticationRequired() && deviceLocked()

/** Whether a pattern, a PIN, a password or a biometric is needed to use the device now. */
internal fun deviceLocked(context: Context): Boolean =
    (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceLocked == true

/** Receives explicit immutable action PendingIntents declared by the core. */
class LiveUpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) =
        ActionTap(Build.VERSION.SDK_INT, ::deviceLocked).take(context, intent)
}

/** Takes the tap of an action on a device of [sdk] that [locked] answers for; tests give both. */
internal class ActionTap(private val sdk: Int, private val locked: (Context) -> Boolean) {
    fun take(context: Context, intent: Intent) {
        if (intent.action != LIVE_UPDATE_ACTION) return
        val activityId = intent.getStringExtra(ACTIVITY_ID_EXTRA) ?: return
        val actionId = intent.getStringExtra(ACTION_ID_EXTRA) ?: return
        val required = {
            intent.getBooleanExtra(AUTHENTICATION_EXTRA, false) || requiresAuthentication(context, activityId, actionId)
        }
        if (withholdsAction(sdk, required) { locked(context) }) {
            Log.i(LOG_TAG, "A live update action that requires authentication was not taken: the device is locked.")
            return
        }
        try {
            LiveUpdateEvents.stored(EventStore(context).appendAction(activityId, actionId))
        } catch (_: Exception) {
            Log.e(LOG_TAG, "Could not persist a live update action.")
        }
    }
}

/**
 * What the stored receipt says of an action: an intent of an earlier version does not say it. Receipts that cannot
 * be read protect the action.
 */
internal fun requiresAuthentication(context: Context, activityId: String, actionId: String): Boolean = try {
    AndroidAdapter(context).requiresAuthentication(activityId, actionId)
} catch (_: Exception) {
    true
}

/** Receives explicit immutable delete PendingIntents from system dismissal. */
class LiveUpdateDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LIVE_UPDATE_DISMISSED) return
        val activityId = intent.getStringExtra(ACTIVITY_ID_EXTRA) ?: return
        try {
            LiveUpdateEvents.stored(EventStore(context).appendDismissed(activityId))
        } catch (_: Exception) {
            Log.e(LOG_TAG, "Could not persist a live update dismissal.")
        }
    }
}

/**
 * Receives the alarm of a scheduled start and begins what is due from the stored receipts, without the
 * application's own code. A host that also declares it for `BOOT_COMPLETED` has the alarm set again after the
 * device restarted.
 */
class LiveUpdateStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LIVE_UPDATE_START && intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            AndroidAdapter(context).startDue()
        } catch (_: Exception) {
            Log.e(LOG_TAG, "Could not begin a scheduled live update.")
        }
    }
}

private const val LOG_TAG = "LiveUpdate"
