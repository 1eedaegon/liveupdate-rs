package io.github.eedaegon.liveupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionAuthenticationTest {
    // LU-V2-ACTION-3
    @Test
    fun aTapIsDroppedOnlyBelowApi31OnALockedDeviceForAnActionThatRequiresIt() {
        assertTrue(withholdsAction(sdk = 30, authenticationRequired = { true }) { true })
        assertTrue(withholdsAction(sdk = 26, authenticationRequired = { true }) { true })

        // Unlocked, the tap is taken.
        assertFalse(withholdsAction(sdk = 30, authenticationRequired = { true }) { false })
        // From API 31 Android asked before it sent the tap.
        assertFalse(withholdsAction(sdk = 31, authenticationRequired = { true }) { true })
        assertFalse(withholdsAction(sdk = 37, authenticationRequired = { true }) { true })
        // An action that does not require it is taken from a locked device, as before.
        assertFalse(withholdsAction(sdk = 30, authenticationRequired = { false }) { true })
    }

    // LU-V2-ACTION-3
    @Test
    fun eachQuestionIsAskedOnlyWhenTheTapDependsOnIt() {
        val asked = mutableListOf<String>()
        fun required(answer: Boolean) = {
            asked += "required"
            answer
        }
        val locked = {
            asked += "locked"
            true
        }

        // From API 31 neither the receipts nor the keyguard are read.
        withholdsAction(sdk = 31, authenticationRequired = required(true), deviceLocked = locked)
        assertEquals(emptyList<String>(), asked)

        withholdsAction(sdk = 30, authenticationRequired = required(false), deviceLocked = locked)
        assertEquals(listOf("required"), asked)

        asked.clear()
        withholdsAction(sdk = 30, authenticationRequired = required(true), deviceLocked = locked)
        assertEquals(listOf("required", "locked"), asked)
    }
}
