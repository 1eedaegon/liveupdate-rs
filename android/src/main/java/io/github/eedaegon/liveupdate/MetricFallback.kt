package io.github.eedaegon.liveupdate

import java.text.NumberFormat
import java.util.Locale

/** More fraction digits than a float has: a decimal is written with all of its own. */
private const val ALL_FRACTION_DIGITS = 340

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
 * each text, the label and then the value. A number has [locale]'s separators and is not rounded: a decimal has
 * the digits of the float that Android takes. A stopwatch and a countdown run, which a text does not: they have
 * no line.
 */
internal fun metricLines(metrics: List<ParsedMetric>, locale: Locale): List<String> {
    // One formatter for the whole style, and none for a style without a number.
    val numbers by lazy(LazyThreadSafetyMode.NONE) {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = ALL_FRACTION_DIGITS }
    }
    return metrics.mapNotNull { metric ->
        val value = when (val item = metric.value) {
            is ParsedMetricValue.Integer -> withUnit(numbers.format(item.value.toLong()), item.unit)
            // The shortest decimal that is this float, and not the digits of the double nearest to it.
            is ParsedMetricValue.Decimal -> withUnit(numbers.format(item.value.toString().toBigDecimal()), item.unit)
            is ParsedMetricValue.Text -> item.value
            is ParsedMetricValue.Stopwatch, is ParsedMetricValue.Countdown -> null
        }
        value?.let { "${metric.label} $it" }
    }
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
