package io.github.gon.liveupdate

import android.content.Context

/** The JNI functions of `android/native-test`, a Rust library that uses the crate. */
internal object NativeRust {
    init {
        System.loadLibrary("liveupdate_android_native_test")
    }

    /** Points ndk-context at [context], as a Rust host runtime does when its library loads. */
    external fun initialize(context: Context)

    /** Clears ndk-context, as before any host initialized it. */
    external fun release()

    /** Runs one scenario of the native library and returns its JSON outcome. */
    external fun run(scenario: String, input: String): String

    /** Writes the Rust coverage counters to [path]; false when built without coverage. */
    external fun writeCoverage(path: String): Boolean
}
