package io.github.eedaegon.liveupdate.consumer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.eedaegon.liveupdate.LiveUpdateEvents

/** What a content tap or a link opens: it reports the opening, as the README says a host does, and closes. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        opened(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        opened(intent)
    }

    private fun opened(intent: Intent?) {
        report("opened", "{\"ours\":${LiveUpdateEvents.opened(this, intent)},\"data\":\"${intent?.dataString ?: ""}\"}")
        finish()
    }
}
