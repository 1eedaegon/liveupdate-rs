package io.github.eedaegon.liveupdate.consumer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.eedaegon.liveupdate.LiveUpdatePush
import org.json.JSONObject

/**
 * Plays an application's messaging service: a message's data map, handed to `LiveUpdatePush.handle` as
 * `onMessageReceived` does, in a process that the broadcast started as FCM starts one. The answer is logged under
 * the script's nonce.
 */
class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val member = intent.getStringExtra("member") ?: return
        val name = intent.getStringExtra("nonce")?.let { "push#$it" } ?: "push"
        val pending = goAsync()
        Thread {
            try {
                val handled = LiveUpdatePush.handle(context, mapOf(LiveUpdatePush.DATA_KEY to member))
                report(name, JSONObject().put("handled", handled).toString())
            } finally {
                pending.finish()
            }
        }.start()
    }
}
