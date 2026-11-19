package io.github.eedaegon.liveupdate.consumer

import android.content.Context

/** The application's Rust code. */
object Native {
    init {
        System.loadLibrary("liveupdate_consumer")
    }

    /** Plays the runtime that sets up `ndk-context`, as a Rust window runtime does when it loads. */
    external fun initialize(context: Context)

    /** Runs one scenario and answers `{"ok": …}` or `{"error": {"code", "message"}}`. */
    external fun run(scenario: String, input: String): String
}
