package io.github.eedaegon.liveupdate

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveUpdateEventsTest {
    @After
    fun clear() = LiveUpdateEvents.setListener(null)

    // LU-V2-EVENT-6
    @Test
    fun theListenerHearsEachStoredEventUntilItIsCleared() {
        var heard = 0
        LiveUpdateEvents.setListener { heard += 1 }

        LiveUpdateEvents.stored()
        LiveUpdateEvents.stored()
        LiveUpdateEvents.setListener(null)
        LiveUpdateEvents.stored()

        assertEquals(2, heard)
    }

    // LU-V2-EVENT-6
    @Test
    fun aNewListenerReplacesTheOldOne() {
        val heard = mutableListOf<String>()
        LiveUpdateEvents.setListener { heard += "old" }
        LiveUpdateEvents.setListener { heard += "new" }

        LiveUpdateEvents.stored()

        assertEquals(listOf("new"), heard)
    }

    // LU-V2-EVENT-6
    @Test
    fun aListenerThatThrowsDoesNotReachTheReceiver() {
        LiveUpdateEvents.setListener { throw IllegalStateException("app failure") }

        LiveUpdateEvents.stored()
    }
}
