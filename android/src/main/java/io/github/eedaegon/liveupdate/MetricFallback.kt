package io.github.eedaegon.liveupdate

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** The fraction digits `Notification.Metric.FixedFloat` shows at the most, where it is not told otherwise. */
private const val MAX_FRACTION_DIGITS = 2

/** A metric as the renderer read it. */
internal data class ParsedMetric(val label: String, val value: ParsedMetricValue, val semantic: Int)

internal sealed interface ParsedMetricValue {
    data class Integer(val value: Int, val unit: String?) : ParsedMetricValue
    data class Decimal(val value: Float, val unit: String?) : ParsedMetricValue
    data class Text(val value: String) : ParsedMetricValue
    data class Stopwatch(val startedAt: Long) : ParsedMetricValue
    data class Countdown(val endsAt: Long) : ParsedMetricValue
}

/** A header clock: the time it counts from or to, in Unix milliseconds. */
internal data class MetricClock(val time: Long, val countsDown: Boolean)

/**
 * What a metric style shows as text where Android has no `MetricStyle`, below API 37: a line for each number and
 * each text, the label and then the value. A number has [locale]'s separators, and a decimal at most two
 * fraction digits, as the system's style writes the same float. A stopwatch and a countdown run, which a text
 * does not: they have no line.
 */
internal fun metricLines(metrics: List<ParsedMetric>, locale: Locale): List<String> {
    // One formatter for the whole style, and none for a style without a number.
    val numbers by lazy(LazyThreadSafetyMode.NONE) { NumberFormat.getNumberInstance(locale) }
    return metrics.mapNotNull { metric ->
        val value = when (val item = metric.value) {
            is ParsedMetricValue.Integer -> withUnit(numbers.format(item.value.toLong()), item.unit)
            is ParsedMetricValue.Decimal -> withUnit(numbers.format(rounded(item.value)), item.unit)
            is ParsedMetricValue.Text -> item.value
            is ParsedMetricValue.Stopwatch, is ParsedMetricValue.Countdown -> null
        }
        value?.let { "${metric.label} $it" }
    }
}

/** [value] at two fraction digits, and zero without a sign for what rounds to nothing. */
private fun rounded(value: Float): BigDecimal {
    val rounded = BigDecimal(value.toDouble()).setScale(MAX_FRACTION_DIGITS, RoundingMode.HALF_EVEN)
    return if (rounded.signum() == 0) BigDecimal.ZERO else rounded.stripTrailingZeros()
}

private fun withUnit(number: String, unit: String?): String = if (unit == null) number else "$number $unit"

/** The first stopwatch or countdown among [metrics]: the one clock a notification's header has room for. */
internal fun metricClock(metrics: List<ParsedMetric>): MetricClock? = metrics.firstNotNullOfOrNull { metric ->
    when (val item = metric.value) {
        is ParsedMetricValue.Stopwatch -> MetricClock(item.startedAt, countsDown = false)
        is ParsedMetricValue.Countdown -> MetricClock(item.endsAt, countsDown = true)
        else -> null
    }
}
