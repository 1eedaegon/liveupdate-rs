package io.github.eedaegon.liveupdate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetsTest {
    // LU-TARGET-3
    @Test
    fun aComponentIsAClassNameOfAnyPackageAndAnActionPrintableAscii() {
        for (name in listOf(
            "dev.fremen.app.FremenService",
            "a.B",
            "dev.fremen.app.FremenService\$Inner",
            "_x.y",
            "a." + "b".repeat(254),
        )) {
            assertTrue(name, isValidComponent(name))
        }
        for (name in listOf(
            "",
            "FremenService",
            ".FremenService",
            "dev.fremen.",
            "dev..FremenService",
            "dev.1fremen.Service",
            "dev.fremen.Fremen Service",
            "dev/fremen/FremenService",
            "a." + "b".repeat(255),
            // ASCII only, as the crate checks it: Rust and Java do not agree on Unicode's letters.
            "dev.fremen.app.Ünicode",
            "dev.fremen.app.Ⅷ",
        )) {
            assertFalse(name, isValidComponent(name))
        }
        for (action in listOf("dev.fremen.app.RECORD", "RECORD", "a-b_c:d", "A".repeat(256))) {
            assertTrue(action, isValidIntentAction(action))
        }
        for (action in listOf("", " RECORD", "RE CORD", "RECORDÜ", "A".repeat(257))) {
            assertFalse(action, isValidIntentAction(action))
        }
    }

    // LU-TARGET-5
    @Test
    fun belowApi31ALockedDeviceWithholdsATapThatRequiresAuthentication() {
        // The rule of the AAR's receiver, asked of a tap that the application's own component took.
        assertTrue(withholdsAction(30, { true }) { true })
        assertFalse(withholdsAction(30, { false }) { true })
        assertFalse(withholdsAction(30, { true }) { false })
        assertFalse(withholdsAction(31, { true }) { true })
    }

    // LU-V2-EVENT-9
    @Test
    fun aStoredEventIsHandedOnWithItsContent() {
        fun event(kind: JSONObject) = JSONObject()
            .put("sequence", 7).put("activityId", "ride").put("occurredAt", 1_000).put("kind", kind)

        val handled = LiveUpdateEvent.of(event(JSONObject().put("kind", "handled").put("actionId", "record")))
        assertEquals(7L, handled.sequence)
        assertEquals("ride", handled.activityId)
        assertEquals(1_000L, handled.occurredAt)
        assertEquals("handled", handled.kind)
        assertEquals("record", handled.actionId)

        val dismissed = LiveUpdateEvent.of(event(JSONObject().put("kind", "dismissed")))
        assertEquals("dismissed", dismissed.kind)
        assertNull(dismissed.actionId)
        assertEquals("LiveUpdateEvent(sequence=7, activityId=ride, occurredAt=1000, kind=dismissed, actionId=null)", dismissed.toString())
    }
}
