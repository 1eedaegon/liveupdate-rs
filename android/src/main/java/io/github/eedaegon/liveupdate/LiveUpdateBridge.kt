package io.github.eedaegon.liveupdate

import android.content.Context

/** JNI entry point used by the `liveupdate` Rust crate. */
object LiveUpdateBridge {
    /** Dispatches one bounded Rust driver command against Android framework APIs. */
    @JvmStatic
    fun invoke(context: Context, command: String, payload: String): String =
        LiveUpdateProtocol.dispatch(AndroidAdapter(context.applicationContext), command, payload)
}
