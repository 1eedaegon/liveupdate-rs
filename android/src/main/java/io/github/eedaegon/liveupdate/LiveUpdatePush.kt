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

    /**
     * The member of a data message for a layer above liveupdate, which the host's library takes through its own
     * native entry, [nativeLayer]: a crate that keeps more of an activity than liveupdate's receipt.
     */
    const val LAYER_KEY = "liveupdate.layer"

    /** The manifest's meta-data that names the host's library, as `System.loadLibrary` takes it. */
    const val LIBRARY = "io.github.eedaegon.liveupdate.LIBRARY"

    /**
     * Runs a message's payload on the calling thread, and answers whether the message was the library's: false
     * for a map without [DATA_KEY] and [LAYER_KEY], which the host handles itself. A map with [LAYER_KEY] goes to
     * the layer alone, also beside [DATA_KEY]: one message has one owner. A library that cannot be loaded or lacks
     * the entry, and an answer without a command, are logged; neither names an activity to store an event of.
     */
    @JvmStatic
    fun handle(context: Context, data: Map<String, String>): Boolean {
        val layer = data[LAYER_KEY]
        val member = layer ?: data[DATA_KEY] ?: return false
        val application = context.applicationContext ?: context
        val outcome = try {
            library(application)?.let(System::loadLibrary)
            JSONObject(if (layer != null) nativeLayer(application, layer) else nativeHandle(application, member))
        } catch (_: UnsatisfiedLinkError) {
            Log.e(LOG_TAG, "No loaded library has the entry for this push: name it in the meta-data $LIBRARY.")
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

    /** The entry of a layer above liveupdate, which the host's library exports when it has such a layer. */
    @JvmStatic
    private external fun nativeLayer(context: Context, member: String): String
}
