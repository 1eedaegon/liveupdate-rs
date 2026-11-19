package io.github.eedaegon.liveupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearOngoingTest {
    // LU-WEAR-1
    @Test
    fun theOngoingActivityIsOfferedOnAWatchOfApi30To36ThatHasTheLibrary() {
        assertTrue(offersWearOngoing(watch = true, sdk = 30) { true })
        assertTrue(offersWearOngoing(watch = true, sdk = 34) { true })
        assertTrue(offersWearOngoing(watch = true, sdk = 36) { true })

        // Below API 30 a watch ignores it, and from API 37 it shows a Live Update.
        assertFalse(offersWearOngoing(watch = true, sdk = 29) { true })
        assertFalse(offersWearOngoing(watch = true, sdk = 37) { true })
        assertFalse(offersWearOngoing(watch = false, sdk = 34) { true })
        assertFalse(offersWearOngoing(watch = true, sdk = 34) { false })

        // The library is looked for only on such a watch.
        var asked = 0
        val library = {
            asked += 1
            true
        }
        offersWearOngoing(watch = false, sdk = 34, library = library)
        offersWearOngoing(watch = true, sdk = 29, library = library)
        offersWearOngoing(watch = true, sdk = 37, library = library)
        assertEquals(0, asked)
        offersWearOngoing(watch = true, sdk = 34, library = library)
        assertEquals(1, asked)
    }

    // LU-WEAR-3
    @Test
    fun aClocksTimeMovesToTheTimeSinceTheDeviceStarted() {
        // Ten minutes ahead of the wall clock is ten minutes ahead of the elapsed time.
        assertEquals(605_000L, elapsedAt(time = 1_600_000, wallNow = 1_000_000, elapsedNow = 5_000))
        // A minute ago is a minute behind, also before the device started.
        assertEquals(-55_000L, elapsedAt(time = 940_000, wallNow = 1_000_000, elapsedNow = 5_000))
        assertEquals(5_000L, elapsedAt(time = 1_000_000, wallNow = 1_000_000, elapsedNow = 5_000))
    }

    // LU-WEAR-7
    @Test
    fun aShownOngoingActivityIsWithdrawnBeforeAPostWithoutOneByWhatThisProcessPostedLast() {
        var asked = 0
        fun listed(answer: Boolean): () -> Boolean = {
            asked += 1
            answer
        }
        // A post that carries one replaces whatever is shown, and nothing is asked.
        for (remembered in listOf(true, false, null)) {
            assertFalse(withdrawsOngoing(remembered, carries = true, listed = listed(true)))
        }
        // What this process posted last decides: Android lists a post only some time after it.
        assertTrue(withdrawsOngoing(remembered = true, carries = false, listed = listed(false)))
        assertFalse(withdrawsOngoing(remembered = false, carries = false, listed = listed(true)))
        assertEquals(0, asked)
        // Without a memory of it, in a process that started since, Android's list is asked once.
        assertTrue(withdrawsOngoing(remembered = null, carries = false, listed = listed(true)))
        assertFalse(withdrawsOngoing(remembered = null, carries = false, listed = listed(false)))
        assertEquals(2, asked)
    }
}
