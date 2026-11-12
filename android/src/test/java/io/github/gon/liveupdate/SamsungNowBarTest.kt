package io.github.gon.liveupdate

import org.junit.Assert.assertEquals
import org.junit.Test

class SamsungNowBarTest {
    // LU-V2-SAMSUNG-1
    @Test
    fun extrasAreClosedOptInAndApi37Only() {
        assertEquals(
            mapOf(
                "android.ongoingActivityNoti.style" to 1,
                "android.ongoingActivityNoti.primaryInfo" to "Order",
                "android.ongoingActivityNoti.secondaryInfo" to "Preparing",
                "android.ongoingActivityNoti.nowbarPrimaryInfo" to "Order",
                "android.ongoingActivityNoti.nowbarSecondaryInfo" to "Preparing",
            ),
            SamsungNowBar.extras(
                sdk = 37,
                primary = "Order",
                secondary = "Preparing",
            ),
        )
        assertEquals(emptyMap<String, Any>(), SamsungNowBar.extras(36, "Order", "Preparing"))
        assertEquals(
            setOf(
                "android.ongoingActivityNoti.style",
                "android.ongoingActivityNoti.primaryInfo",
                "android.ongoingActivityNoti.nowbarPrimaryInfo",
            ),
            SamsungNowBar.extras(37, "Order", null).keys,
        )
    }
}
