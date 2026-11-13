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

private const val LOG_TAG = "LiveUpdate"
