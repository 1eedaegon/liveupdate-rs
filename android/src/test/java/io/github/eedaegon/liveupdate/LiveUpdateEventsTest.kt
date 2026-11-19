package io.github.eedaegon.liveupdate

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

private fun event(sequence: Long, kind: String) = JSONObject()
    .put("sequence", sequence).put("activityId", "ride").put("occurredAt", 1_000)
    .put("kind", JSONObject().put("kind", kind).apply { if (kind == "handled") put("actionId", "record") })

class LiveUpdateEventsTest {
    private val natives = mutableListOf<LiveUpdateNativeListener>()

    @After
    fun clear() {
        LiveUpdateEvents.setListener(null)
        natives.forEach(LiveUpdateEvents::removeNativeListener)
    }

    // LU-V2-EVENT-6
    @Test
    fun theListenerHearsEachStoredEventUntilItIsCleared() {
        var heard = 0
        LiveUpdateEvents.setListener { heard += 1 }

        LiveUpdateEvents.stored(event(1, "dismissed"))
        LiveUpdateEvents.stored(event(2, "dismissed"))
        LiveUpdateEvents.setListener(null)
        LiveUpdateEvents.stored(event(3, "dismissed"))

        assertEquals(2, heard)
    }

    // LU-V2-EVENT-6
    @Test
    fun aNewListenerReplacesTheOldOne() {
        val heard = mutableListOf<String>()
        LiveUpdateEvents.setListener { heard += "old" }
        LiveUpdateEvents.setListener { heard += "new" }

        LiveUpdateEvents.stored(event(1, "dismissed"))

        assertEquals(listOf("new"), heard)
    }

    // LU-V2-EVENT-6
    @Test
    fun aListenerThatThrowsDoesNotReachTheReceiver() {
        LiveUpdateEvents.setListener { throw IllegalStateException("app failure") }

        LiveUpdateEvents.stored(event(1, "dismissed"))
    }

    // LU-V2-EVENT-9
    @Test
    fun nativeListenersAreToldOfEachEventWithItsContentAndOneThatThrowsStopsNone() {
        val first = mutableListOf<LiveUpdateEvent>()
        val second = mutableListOf<LiveUpdateEvent>()
        var told = 0
        LiveUpdateEvents.setListener { told += 1 }
        for (listener in listOf(
            LiveUpdateNativeListener { throw IllegalStateException("app failure") },
            LiveUpdateNativeListener { first += it },
            LiveUpdateNativeListener { second += it },
        )) {
            natives += listener
            LiveUpdateEvents.addNativeListener(listener)
        }

        LiveUpdateEvents.stored(event(1, "dismissed"))
        LiveUpdateEvents.stored(event(2, "handled"))
        LiveUpdateEvents.removeNativeListener(natives.last())
        LiveUpdateEvents.stored(event(3, "later"))

        assertEquals(3, told)
        assertEquals(listOf("dismissed", "handled", "later"), first.map { it.kind })
        assertEquals(listOf(null, "record", null), first.map { it.actionId })
        assertEquals(listOf(1L, 2L), second.map { it.sequence })
    }
}
