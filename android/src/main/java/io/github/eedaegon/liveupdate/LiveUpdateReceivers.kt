package io.github.eedaegon.liveupdate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

internal const val LIVE_UPDATE_ACTION = "io.github.1eedaegon.liveupdate.ACTION"
internal const val LIVE_UPDATE_DISMISSED = "io.github.1eedaegon.liveupdate.DISMISSED"
internal const val ACTIVITY_ID_EXTRA = "activityId"
internal const val ACTION_ID_EXTRA = "actionId"

/** Receives explicit immutable action PendingIntents declared by the core. */
class LiveUpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LIVE_UPDATE_ACTION) return
        val activityId = intent.getStringExtra(ACTIVITY_ID_EXTRA) ?: return
        val actionId = intent.getStringExtra(ACTION_ID_EXTRA) ?: return
        try {
            EventStore(context).appendAction(activityId, actionId)
            LiveUpdateEvents.stored()
        } catch (_: Exception) {
            Log.e(LOG_TAG, "Could not persist a live update action.")
        }
    }
}

/** Receives explicit immutable delete PendingIntents from system dismissal. */
class LiveUpdateDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LIVE_UPDATE_DISMISSED) return
        val activityId = intent.getStringExtra(ACTIVITY_ID_EXTRA) ?: return
        try {
            EventStore(context).appendDismissed(activityId)
            LiveUpdateEvents.stored()
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
