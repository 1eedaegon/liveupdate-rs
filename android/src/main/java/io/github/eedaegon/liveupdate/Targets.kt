package io.github.eedaegon.liveupdate

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ComponentInfo
import android.net.Uri

/** Marks an intent that the target of an action was given: the component's own intents have none. */
internal const val EXTRA_TARGET = "io.github.1eedaegon.liveupdate.target"
internal const val EXTRA_AUTHENTICATION = "io.github.1eedaegon.liveupdate.authenticationRequired"

internal val TARGET_KINDS = setOf("service", "foregroundService", "broadcast")
private const val MAX_COMPONENT = 256
private const val MAX_INTENT_ACTION = 256

/**
 * Two or more ASCII Java identifiers joined by dots, at most 256 characters: a class of any Java package. Java allows
 * other letters too, but Rust and Java do not agree on which: the crate checks the same.
 */
internal fun isValidComponent(name: String): Boolean {
    if (name.length > MAX_COMPONENT || '.' !in name) return false
    return name.split('.').all { segment ->
        segment.isNotEmpty() && (segment[0].isAsciiLetter() || segment[0] == '_' || segment[0] == '$') &&
            segment.all { it.isAsciiLetter() || it in '0'..'9' || it == '_' || it == '$' }
    }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/** An intent action: printable ASCII without spaces, at most 256 characters. */
internal fun isValidIntentAction(action: String): Boolean =
    action.length in 1..MAX_INTENT_ACTION && action.all { it in '!'..'~' }

/**
 * The intent that a tap on the action [actionId] of [activityId] delivers to [component], a class in the
 * application's package. Its data makes the PendingIntent of each activity's action one of its own.
 */
internal fun targetIntent(
    context: Context,
    activityId: String,
    actionId: String,
    component: String,
    action: String?,
    authentication: Boolean,
): Intent = Intent(action)
    .setComponent(ComponentName(context.packageName, component))
    .setData(Uri.Builder().scheme("liveupdate").authority("target").appendPath(activityId).appendPath(actionId).build())
    .putExtra(EXTRA_TARGET, true)
    .putExtra(EXTRA_ACTIVITY_ID, activityId)
    .putExtra(EXTRA_ACTION_ID, actionId)
    .putExtra(EXTRA_AUTHENTICATION, authentication)

/** The PendingIntents of action targets: immutable, explicit, and only to a component the application has. */
internal class TargetResolver(private val context: Context) {
    /**
     * The PendingIntent of [kind] to [component]; null when Android resolves no such component in the application,
     * or one that is exported, which any application could send the marks of a tap, or one in another process,
     * whose view of the event store is not the application's.
     */
    fun open(
        activityId: String,
        actionId: String,
        kind: String,
        component: String,
        action: String?,
        authentication: Boolean,
    ): PendingIntent? {
        val intent = targetIntent(context, activityId, actionId, component, action, authentication)
        val resolved: ComponentInfo = when (kind) {
            "broadcast" -> context.packageManager.queryBroadcastReceivers(intent, 0).firstOrNull()?.activityInfo
            else -> context.packageManager.resolveService(intent, 0)?.serviceInfo
        } ?: return null
        if (resolved.exported || resolved.processName != context.applicationInfo.processName) return null
        val code = "${activityId.length}:$activityId:$actionId".hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return when (kind) {
            "service" -> PendingIntent.getService(context, code, intent, flags)
            "foregroundService" -> PendingIntent.getForegroundService(context, code, intent, flags)
            else -> PendingIntent.getBroadcast(context, code, intent, flags)
        }
    }
}
