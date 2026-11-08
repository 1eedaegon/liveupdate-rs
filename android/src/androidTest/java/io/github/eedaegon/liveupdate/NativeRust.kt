package io.github.eedaegon.liveupdate

import android.content.Context

internal object NativeRust {
    init {
        System.loadLibrary("liveupdate_android_native_test")
    }

    external fun run(context: Context): String
}
