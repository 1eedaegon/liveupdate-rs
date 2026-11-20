package io.github.eedaegon.liveupdate

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** The tap of an action's target, as [LiveUpdateActions.take] answers it. */
class LiveUpdateTap internal constructor(
    /** The activity whose notification had the action. */
    val activityId: String,
    /** The action's id, as the snapshot named it. */
    val actionId: String,
    /**
     * True when the application must not act on the tap: below Android 12 the action requires authentication,
     * the device is locked, and Android sent the tap without asking for the unlock.
     */
    val withheld: Boolean,
) {
    override fun toString(): String = "LiveUpdateTap(activityId=$activityId, actionId=$actionId, withheld=$withheld)"
}

/** What the component of an action's target calls with the intent it was given. */
object LiveUpdateActions {
    /**
     * Takes the tap that [intent] delivers to a target, and answers it, or null for an intent that is no tap of a
     * target. Call it first, with the intent of `onStartCommand` or `onReceive`.
     *
     * The tap is recorded as a `handled` event, which Rust and the page drain and do not act on again, and the
     * listeners of [LiveUpdateEvents] are told; a tap of an activity that is no longer stored is answered and not
     * recorded. Below Android 12 a tap of an action that requires authentication on a locked device is answered
     * as withheld and not recorded. The marks are removed from [intent], so that an intent delivered again is no
     * second tap. It never throws: a record that fails is logged. It reads the stored receipts and the event queue
     * on the calling thread, and writes without waiting for the disk; with many large receipts, call it off the
     * main thread.
     */
    @JvmStatic
    fun take(context: Context, intent: Intent?): LiveUpdateTap? =
        TargetTap(Build.VERSION.SDK_INT, ::deviceLocked).take(context, intent)
}

/** Takes the tap of a target on a device of [sdk] that [locked] answers for; tests give both. */
internal class TargetTap(private val sdk: Int, private val locked: (Context) -> Boolean) {
    fun take(context: Context, intent: Intent?): LiveUpdateTap? {
        if (intent == null) return null
        val marks = try {
            if (!intent.getBooleanExtra(EXTRA_TARGET, false)) return null
            val activityId = intent.getStringExtra(EXTRA_ACTIVITY_ID) ?: return null
            val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return null
            // Ids that no notification of the library carries are no tap.
            if (!isValidContractText(activityId, MAX_ID) || !isValidActionId(actionId)) return null
            Triple(activityId, actionId, intent.getBooleanExtra(EXTRA_AUTHENTICATION, false))
        } catch (_: Exception) {
            // The extras of an intent that cannot be read are no tap of a target.
            return null
        }
        val (activityId, actionId, authentication) = marks
        for (extra in listOf(EXTRA_TARGET, EXTRA_ACTIVITY_ID, EXTRA_ACTION_ID, EXTRA_AUTHENTICATION)) {
            intent.removeExtra(extra)
        }
        // One read of the stored receipts, made only when a question needs it.
        val receipt = lazy { runCatching { AndroidAdapter(context).let { it to it.receipt(activityId) } } }
        // Receipts that cannot be read protect the action.
        val required = {
            authentication || receipt.value.fold(
                { (adapter, stored) -> adapter.requiresAuthentication(stored, actionId) },
                { true },
            )
        }
        if (withholdsAction(sdk, required) { locked(context) }) {
            Log.i(LOG_TAG, "A live update action that requires authentication was not taken: the device is locked.")
            return LiveUpdateTap(activityId, actionId, withheld = true)
        }
        try {
            if (receipt.value.getOrThrow().second != null) {
                LiveUpdateEvents.stored(EventStore(context).appendHandled(activityId, actionId))
            }
        } catch (failure: Throwable) {
            // The application has its tap whatever the record does, a class that fails to load included.
            if (failure !is Exception && failure !is LinkageError) throw failure
            Log.e(LOG_TAG, "Could not record a live update action that the application took.")
        }
        return LiveUpdateTap(activityId, actionId, withheld = false)
    }

    private companion object {
        const val LOG_TAG = "LiveUpdate"
        const val MAX_ID = 256
    }
}
