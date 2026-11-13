package io.github.eedaegon.liveupdate

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Marks an intent that a notification of this library opened the application with. */
internal const val EXTRA_ACTIVITY_ID = "io.github.1eedaegon.liveupdate.activityId"
internal const val EXTRA_ACTION_ID = "io.github.1eedaegon.liveupdate.actionId"

internal const val MAX_URL = 2_048
private val SCHEME = Regex("[a-z][a-z0-9+.-]*")

/** None of these names a screen of the application. */
private val REFUSED_SCHEMES = setOf("intent", "content", "file", "javascript", "data", "android-app")

/** A URL the contract takes: absolute, printable ASCII, with a lowercase scheme that is not refused. */
internal fun isValidLink(url: String): Boolean {
    val colon = url.indexOf(':')
    if (colon <= 0 || colon == url.length - 1 || url.length > MAX_URL) return false
    val scheme = url.substring(0, colon)
    return SCHEME.matches(scheme) && scheme !in REFUSED_SCHEMES && url.all { it in '!'..'~' }
}

/** The activity intents a notification opens the application with: immutable, and bound to its package. */
internal class LinkResolver(private val context: Context) {
    /**
     * Opens [url] in this package, or the launch activity without one. The intent names the activity, and the
     * action when one opens it, and is distinct for each. Null when nothing in the package can open it.
     */
    fun open(activityId: String, actionId: String?, url: String?): PendingIntent? {
        val intent = (if (url == null) launch() else view(url)) ?: return null
        intent.putExtra(EXTRA_ACTIVITY_ID, activityId)
        if (actionId != null) intent.putExtra(EXTRA_ACTION_ID, actionId)
        // The length keeps an activity's id apart from an action's, whatever characters the id has.
        val identity = "${activityId.length}:$activityId" + (actionId?.let { ":$it" } ?: "")
        if (Build.VERSION.SDK_INT >= 29) intent.identifier = identity
        return PendingIntent.getActivity(
            context,
            identity.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun launch(): Intent? = context.packageManager.getLaunchIntentForPackage(context.packageName)

    private fun view(url: String): Intent? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(context.packageName)
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }
}
