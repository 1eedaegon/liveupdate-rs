package io.github.eedaegon.liveupdate.consumer

import android.app.Application
import android.util.Log
import io.github.eedaegon.liveupdate.LiveUpdateEvents

internal const val TAG = "LiveUpdateConsumer"

/** Answers one line in the log for the script: the scenario, a space, and its JSON. */
internal fun report(scenario: String, result: String) {
    Log.i(TAG, "$scenario $result")
}

class ConsumerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Native.initialize(this)
        // An event is drained as soon as it is stored, also when the receiver started this process.
        LiveUpdateEvents.setListener {
            Thread { report("events", Native.run("events", "{}")) }.start()
        }
    }
}
