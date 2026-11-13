package io.github.eedaegon.liveupdate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The test package's launch activity, which also opens `liveupdate-test:` URLs. It keeps the intents it was
 * started with and finishes at once.
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        started += Intent(intent)
        finish()
    }

    companion object {
        /** The intents this activity was started with, oldest first. */
        val started = CopyOnWriteArrayList<Intent>()
    }
}
