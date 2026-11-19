package io.github.eedaegon.liveupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

private const val DAY = 24 * 60 * 60 * 1000L
private const val MINUTE = 60_000L

class MetricFallbackTest {
    private fun metric(label: String, value: ParsedMetricValue) = ParsedMetric(label, value, 0)

    // LU-V2-METRIC-4
    @Test
    fun numbersAndTextsAreLinesInTheLocalesDigitsAndClocksHaveNone() {
        val metrics = listOf(
            metric("Steps", ParsedMetricValue.Integer(12_345, "st")),
            metric("Elapsed", ParsedMetricValue.Stopwatch(1_000)),
            metric("Speed", ParsedMetricValue.Decimal(1_234.5f, "km/h")),
            metric("Left", ParsedMetricValue.Countdown(2_000)),
            metric("State", ParsedMetricValue.Text("Ready")),
            metric("Change", ParsedMetricValue.Integer(-7, null)),
            metric("Share", ParsedMetricValue.Decimal(0.1f, null)),
        )

        assertEquals(
            listOf("Steps 12,345 st", "Speed 1,234.5 km/h", "State Ready", "Change -7", "Share 0.1"),
            metricLines(metrics, Locale.US),
        )
        assertEquals(
            listOf("Steps 12.345 st", "Speed 1.234,5 km/h", "State Ready", "Change -7", "Share 0,1"),
            metricLines(metrics, Locale.GERMANY),
        )
        assertEquals(emptyList<String>(), metricLines(metrics.filter { it.label in setOf("Elapsed", "Left") }, Locale.US))
    }

    // LU-V2-METRIC-4
    @Test
    fun aDecimalHasAtMostTwoFractionDigitsAsTheSystemsStyleWritesIt() {
        fun line(value: Float) = metricLines(listOf(metric("V", ParsedMetricValue.Decimal(value, null))), Locale.US).single()

        // What `Notification.MetricStyle` showed on Android 17 for the same values: 3.14, 0 and 0.33.
        assertEquals("V 3.14", line(3.14159f))
        assertEquals("V 0", line(0.0004f))
        assertEquals("V 0.33", line(0.33333334f))
        assertEquals("V 12.5", line(12.5f))
        assertEquals("V 42", line(42f))
        assertEquals("V -0.5", line(-0.5f))
        // The float nearest to 2.675 is a little below it.
        assertEquals("V 2.67", line(2.675f))
        assertEquals("V 0.13", line(0.126f))
        // What rounds to nothing is nothing, without a sign.
        assertEquals("V 0", line(-0.0004f))
        assertEquals("V -0.01", line(-0.006f))
        assertEquals("V 1,234,567.5", line(1234567.5f))
        assertEquals("V 10,000,000,000", line(1.0E10f))
    }

    // LU-V2-METRIC-4
    @Test
    fun theFirstStopwatchOrCountdownIsTheClock() {
        val stopwatch = metric("Elapsed", ParsedMetricValue.Stopwatch(1_000))
        val countdown = metric("Left", ParsedMetricValue.Countdown(2_000))
        val number = metric("Steps", ParsedMetricValue.Integer(1, null))

        assertEquals(MetricClock(1_000, countsDown = false), metricClock(listOf(number, stopwatch, countdown)))
        assertEquals(MetricClock(2_000, countsDown = true), metricClock(listOf(countdown, number, stopwatch)))
        assertNull(metricClock(listOf(number, metric("State", ParsedMetricValue.Text("Ready")))))
        assertNull(metricClock(emptyList()))
    }

    // LU-V2-METRIC-4, LU-ADAPTER-10
    @Test
    fun aClockIsPostedBeforeApi35AtAnyAgeAndFromItUpToFourteenDaysLessTheMargin() {
        for (sdk in listOf(26, 34)) {
            assertTrue(postsClock(sdk, 15 * DAY, MINUTE))
            assertTrue(postsClock(sdk, Long.MAX_VALUE, MINUTE))
        }
        for (sdk in listOf(35, 36, 37)) {
            assertTrue(postsClock(sdk, 14 * DAY - MINUTE, MINUTE))
            assertFalse(postsClock(sdk, 14 * DAY - MINUTE + 1, MINUTE))
            assertTrue(postsClock(sdk, 14 * DAY - 2 * MINUTE, 2 * MINUTE))
            assertFalse(postsClock(sdk, 14 * DAY - 2 * MINUTE + 1, 2 * MINUTE))
            // A clock that has not begun, or a countdown that has not ended, has no age.
            assertTrue(postsClock(sdk, -30 * DAY, MINUTE))
        }
    }
}
