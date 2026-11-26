package io.github.eedaegon.liveupdate

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject

private const val LOG_TAG = "LiveUpdate"

/**
 * A data message from the app's server, for a messaging service of the host to hand over:
 *
 * ```kotlin
 * override fun onMessageReceived(message: RemoteMessage) {
 *     if (LiveUpdatePush.handle(this, message.data)) return
 *     // The host's own messages.
 * }
 * ```
 *
 * The Rust lifecycle runs the member [DATA_KEY] (`liveupdate::push::Payload`) through the crate's native entry
 * (the feature `push`), in the host's library: the manifest's meta-data [LIBRARY] names it, or the host loaded it
 * already. What the push did is stored as a `pushed` event, which `LiveUpdate::events()` drains. The service runs in
 * the application's main process, as the application's own client does: their receipts are one process's.
 */
object LiveUpdatePush {
    /** The member of a data message that carries a payload. */
    const val DATA_KEY = "liveupdate"

    /** The manifest's meta-data that names the host's library, as `System.loadLibrary` takes it. */
    const val LIBRARY = "io.github.eedaegon.liveupdate.LIBRARY"

    /**
     * Runs a message's payload on the calling thread, and answers whether the message was the library's: false
     * for a map without [DATA_KEY], which the host handles itself. A library that cannot be loaded, and a member
     * that is not a payload, are logged; neither names an activity to store an event of.
     */
    @JvmStatic
    fun handle(context: Context, data: Map<String, String>): Boolean {
        val member = data[DATA_KEY] ?: return false
        val application = context.applicationContext ?: context
        val outcome = try {
            library(application)?.let(System::loadLibrary)
            JSONObject(nativeHandle(application, member))
        } catch (_: UnsatisfiedLinkError) {
            Log.e(LOG_TAG, "The host's library with liveupdate's feature `push` is not loaded.")
            return true
        } catch (_: Exception) {
            Log.e(LOG_TAG, "A live update push failed.")
            return true
        }
        if (!outcome.has("command")) {
            Log.e(LOG_TAG, "A live update push was refused: ${outcome.optString("code")}.")
            return true
        }
        try {
            LiveUpdateEvents.stored(EventStore(application).appendPushed(outcome))
        } catch (_: Exception) {
            Log.e(LOG_TAG, "Could not persist what a live update push did.")
        }
        return true
    }

    private fun library(context: Context): String? = try {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString(LIBRARY)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    @JvmStatic
    private external fun nativeHandle(context: Context, member: String): String
}
