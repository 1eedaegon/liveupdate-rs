package io.github.eedaegon.liveupdate.consumer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs the scenario the script names, off the main thread: every call blocks for storage and Android. The answer
 * is logged under the scenario's name and the script's nonce, so that an earlier answer is never taken for it.
 */
class ScenarioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scenario = intent.getStringExtra("scenario") ?: return
        val input = intent.getStringExtra("input") ?: "{}"
        val name = intent.getStringExtra("nonce")?.let { "$scenario#$it" } ?: scenario
        val pending = goAsync()
        Thread {
            try {
                report(name, Native.run(scenario, input))
            } finally {
                pending.finish()
            }
        }.start()
    }
}
