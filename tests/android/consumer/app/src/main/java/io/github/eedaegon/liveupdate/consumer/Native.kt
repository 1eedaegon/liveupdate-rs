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

    /**
     * The same with a client built from [context] alone, as a service's own entry does: in a version with action
     * targets only.
     */
    external fun runFrom(context: Context, scenario: String, input: String): String
}
