package io.github.eedaegon.liveupdate

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.roundToInt

internal const val RECORD_EXTRA = "io.github.1eedaegon.liveupdate.record"

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private const val MAX_TIMESTAMP = 8_640_000_000_000_000L
private const val MAX_PROGRESS_ELEMENTS = 32
private const val MAX_ACTIONS = 3
private const val MAX_METRICS = 3
private const val MAX_SUB_TEXT = 64
private const val INVALID_MESSAGE = "Invalid live update request."
private val ACTION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
private val VALID_STATES = setOf("unknown", "active", "stale", "ended", "dismissed")
private val VALID_PROMOTION_STATES = setOf("promoted", "notPromoted", "notApplicable", "unknown")
private val VALID_STYLES = setOf("standard", "bigText", "progress", "timer", "metric")
private val VALID_PLACEMENTS = setOf("tagged", "foreground")

/** Strict schema-v1/v2 parser and Android system-template renderer. */
internal class NotificationRenderer(
    context: Context,
    private val sdk: Int,
    private val channelId: String,
    private val manufacturer: String,
) {
    private val application = context.applicationContext
    private val icons = IconResolver(application)
    private val links = LinkResolver(application)

    /**
     * The notification of [record]. A revision that alerts is built for [alertChannelId] when the adapter gives
     * one, and may then alert again.
     */
    fun build(record: JSONObject, alertChannelId: String? = null): Notification {
        validateRecordShape(record)
        val id = requiredText(record, "id", 256)
        val origin = requiredObject(record, "origin")
        val snapshot = requiredObject(record, "snapshot")
        val schema = requiredInt(origin, "schemaVersion", 1, 2)
        val title = requiredText(snapshot, "title", 120)
        val status = requiredText(snapshot, "status", 240)
        val builder = baseBuilder(record, id, title, status, alertChannelId ?: channelId)
        if (alertChannelId != null) builder.setOnlyAlertOnce(false)
        when (schema) {
            1 -> applyLegacy(builder, snapshot, requiredText(origin, "template", 16))
            2 -> applyRich(builder, id, snapshot, origin)
        }
        // The last content of an ended activity reports nothing when it goes.
        if (!record.isMissingOrNull("dismissAt")) builder.setDeleteIntent(null)
        return builder.build()
    }

    private fun validateRecordShape(record: JSONObject) {
        requireFields(
            record,
            setOf(
                "id", "origin", "snapshot", "state", "promotionRequested", "promotionState",
                "awaitingObservation", "postFailed",
            ),
            setOf("dismissAt"),
        )
        val state = requiredText(record, "state", 16)
        if (state !in VALID_STATES) invalid()
        if (requiredText(record, "promotionState", 24) !in VALID_PROMOTION_STATES) invalid()
        requiredBoolean(record, "awaitingObservation")
        requiredBoolean(record, "postFailed")
        if (!record.isMissingOrNull("dismissAt")) {
            // Only an ended activity has a time at which its last content goes.
            requiredLong(record, "dismissAt", 0, MAX_TIMESTAMP)
            if (state != "ended") invalid()
        }
        val origin = requiredObject(record, "origin")
        requireFields(
            origin,
            setOf("activityKey", "schemaVersion", "template", "fallback", "visibility", "snapshot"),
            setOf("placement"),
        )
        requiredText(origin, "activityKey", 128)
        if (requiredText(origin, "fallback", 16) !in setOf("none", "notification")) invalid()
        requiredObject(origin, "snapshot")
        val placement = optionalText(origin, "placement", 16)
        if (placement != null && placement !in VALID_PLACEMENTS) invalid()
        if (placement == "foreground" && requiredInt(origin, "schemaVersion", 1, 2) == 1) invalid()
    }

    @SuppressLint("InlinedApi")
    private fun baseBuilder(
        record: JSONObject,
        id: String,
        title: String,
        status: String,
        channel: String,
    ): Notification.Builder {
        val origin = requiredObject(record, "origin")
        // The last content of an ended activity: no longer ongoing, not promoted, and gone at its time.
        val final = !record.isMissingOrNull("dismissAt")
        val extras = Bundle().apply { putString(RECORD_EXTRA, record.toString()) }
        if (sdk >= 36 && !final && requiredBoolean(record, "promotionRequested")) {
            extras.putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true)
        }
        val builder = Notification.Builder(application, channel)
            .setSmallIcon(R.drawable.liveupdate_status)
            .setContentTitle(title)
            .setContentText(status)
            .setOngoing(!final)
            .setOnlyAlertOnce(true)
            .setVisibility(visibility(requiredText(origin, "visibility", 16)))
            .setShowWhen(false)
            .setExtras(extras)
        links.open(id, null, null)?.let(builder::setContentIntent)
        if (final) {
            builder.setTimeoutAfter((record.getLong("dismissAt") - System.currentTimeMillis()).coerceAtLeast(1))
        }
        // Android otherwise holds a service's notification back for ten seconds.
        if (optionalText(origin, "placement", 16) == "foreground" && Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder
    }

    private fun applyLegacy(builder: Notification.Builder, snapshot: JSONObject, template: String) {
        requireFields(snapshot, setOf("revision", "title", "status"), setOf("progress", "timer", "staleAt"))
        validateCommonSnapshot(snapshot)
        when (template) {
            "progress" -> {
                if (snapshot.isMissingOrNull("progress") || !snapshot.isMissingOrNull("timer")) invalid()
                val value = requiredDouble(snapshot, "progress")
                if (value !in 0.0..1.0) invalid()
                builder.setCategory(Notification.CATEGORY_PROGRESS)
                    .setProgress(1000, (value * 1000).roundToInt(), false)
            }
            "timer" -> {
                if (!snapshot.isMissingOrNull("progress")) invalid()
                val timer = requiredObject(snapshot, "timer")
                val (startedAt, endsAt) = timer(timer)
                builder.setCategory(Notification.CATEGORY_STATUS)
                    .setWhen(endsAt).setShowWhen(true).setUsesChronometer(true)
                    .setChronometerCountDown(true)
                if (startedAt < 0) invalid()
            }
            else -> invalid()
        }
    }

    private fun applyRich(
        builder: Notification.Builder,
        activityId: String,
        snapshot: JSONObject,
        origin: JSONObject,
    ) {
        requireFields(snapshot, setOf("revision", "title", "status", "presentation"), setOf("staleAt"))
        validateCommonSnapshot(snapshot)
        val presentation = requiredObject(snapshot, "presentation")
        requireFields(
            presentation,
            setOf("style"),
            setOf(
                "shortCriticalText", "largeIcon", "actions", "samsungNowBar", "chronometer", "openUrl", "alert",
                "publicVersion", "smallIcon", "accentColor", "subText",
            ),
        )
        val style = requiredObject(presentation, "style")
        val kind = requiredText(style, "kind", 16)
        // The start named a system style; an update may carry another one.
        if (requiredText(origin, "template", 16) !in VALID_STYLES) invalid()
        applyStyle(builder, style, kind)
        optionalObject(presentation, "chronometer")?.let { chronometer ->
            // The timer style is that clock alone.
            if (kind == "timer") invalid()
            applyClock(builder, chronometer)
        }
        applyPresentationOptions(
            builder,
            activityId,
            presentation,
            requiredText(snapshot, "title", 120),
            requiredText(snapshot, "status", 240),
        )
        applyExtensions(builder, activityId, presentation, origin)
    }

    private fun applyExtensions(
        builder: Notification.Builder,
        activityId: String,
        presentation: JSONObject,
        origin: JSONObject,
    ) {
        optionalBoolean(presentation, "alert")
        optionalText(presentation, "openUrl", MAX_URL)?.let { url ->
            builder.setContentIntent(link(activityId, null, url))
        }
        val smallIcon = optionalObject(presentation, "smallIcon")?.let { source ->
            // Android tints a small icon's shape, which a resource has.
            if (source.optionalString("kind") != "resource") invalid()
            icons.resolve(source)
        }
        smallIcon?.let(builder::setSmallIcon)
        if (!presentation.isMissingOrNull("accentColor")) {
            builder.setColor(requiredLong(presentation, "accentColor", 0, 0xffff_ffffL).toInt())
        }
        optionalText(presentation, "subText", MAX_SUB_TEXT)?.let(builder::setSubText)
        optionalObject(presentation, "publicVersion")?.let { public ->
            requireFields(public, setOf("title", "status"))
            // Only a private activity has content that a lock screen hides.
            if (requiredText(origin, "visibility", 16) != "private") invalid()
            val shown = Notification.Builder(application, channelId)
                .setContentTitle(requiredText(public, "title", 120))
                .setContentText(requiredText(public, "status", 240))
            if (smallIcon != null) shown.setSmallIcon(smallIcon) else shown.setSmallIcon(R.drawable.liveupdate_status)
            builder.setPublicVersion(shown.build())
        }
    }

    /** The intent that opens [url] in the application; a URL the contract refuses or nothing opens is invalid. */
    private fun link(activityId: String, actionId: String?, url: String): PendingIntent {
        if (!isValidLink(url)) invalid()
        return links.open(activityId, actionId, url) ?: invalid()
    }

    private fun applyStyle(builder: Notification.Builder, style: JSONObject, kind: String) {
        when (kind) {
            "standard" -> {
                requireFields(style, setOf("kind"))
                builder.setCategory(Notification.CATEGORY_STATUS)
            }
            "bigText" -> applyBigText(builder, style)
            "progress" -> applyProgress(builder, style)
            "timer" -> applyTimer(builder, style)
            "metric" -> applyMetric(builder, style)
            else -> invalid()
        }
    }

    private fun applyBigText(builder: Notification.Builder, style: JSONObject) {
        requireFields(style, setOf("kind", "value"))
        val value = requiredObject(style, "value")
        requireFields(value, setOf("text"), setOf("summary"))
        val systemStyle = Notification.BigTextStyle().bigText(requiredText(value, "text", 4_000))
        optionalText(value, "summary", 240)?.let(systemStyle::setSummaryText)
        builder.setCategory(Notification.CATEGORY_STATUS).setStyle(systemStyle)
    }

    private fun applyProgress(builder: Notification.Builder, style: JSONObject) {
        requireFields(style, setOf("kind", "value"))
        val value = requiredObject(style, "value")
        requireFields(
            value,
            setOf("progress", "segments"),
            setOf("indeterminate", "styledByProgress", "points", "startIcon", "endIcon", "trackerIcon"),
        )
        val parsed = parseProgress(value)
        builder.setCategory(Notification.CATEGORY_PROGRESS)
        if (sdk >= 36 && Build.VERSION.SDK_INT >= 36) {
            applySystemProgress(builder, parsed)
        } else {
            builder.setProgress(parsed.maximum, parsed.progress, parsed.indeterminate)
        }
    }

    private fun parseProgress(value: JSONObject): ParsedProgress {
        val segments = requiredArray(value, "segments")
        if (segments.length() !in 1..MAX_PROGRESS_ELEMENTS) invalid()
        val segmentIds = mutableSetOf<Int>()
        var maximum = 0L
        val parsedSegments = (0 until segments.length()).map { index ->
            parseSegment(segments.getJSONObject(index), segmentIds).also { maximum += it.length }
        }
        if (maximum !in 1..Int.MAX_VALUE.toLong()) invalid()
        val points = optionalArray(value, "points") ?: JSONArray()
        val parsedPoints = parsePoints(points, maximum.toInt())
        val progress = requiredInt(value, "progress", 0, maximum.toInt())
        return ParsedProgress(
            progress,
            maximum.toInt(),
            optionalBoolean(value, "indeterminate") ?: false,
            optionalBoolean(value, "styledByProgress") ?: false,
            parsedSegments,
            parsedPoints,
            optionalIcon(value, "startIcon"),
            optionalIcon(value, "endIcon"),
            optionalIcon(value, "trackerIcon"),
        )
    }

    private fun parseSegment(value: JSONObject, ids: MutableSet<Int>): ParsedSegment {
        requireFields(value, setOf("length"), setOf("id", "color", "semanticStyle"))
        val length = requiredInt(value, "length", 1, Int.MAX_VALUE)
        val id = optionalInt(value, "id", 0, Int.MAX_VALUE)
        if (id != null && !ids.add(id)) invalid()
        return ParsedSegment(length, id, optionalColor(value, "color"), optionalSemantic(value))
    }

    private fun parsePoints(points: JSONArray, maximum: Int): List<ParsedPoint> {
        if (points.length() > MAX_PROGRESS_ELEMENTS) invalid()
        val ids = mutableSetOf<Int>()
        var previous = 0
        return (0 until points.length()).map { index ->
            val value = points.getJSONObject(index)
            requireFields(value, setOf("position"), setOf("id", "color", "semanticStyle"))
            val position = requiredInt(value, "position", 1, maximum)
            val id = optionalInt(value, "id", 0, Int.MAX_VALUE)
            if (position <= previous || id != null && !ids.add(id)) invalid()
            previous = position
            ParsedPoint(position, id, optionalColor(value, "color"), optionalSemantic(value))
        }
    }

    @SuppressLint("NewApi")
    private fun applySystemProgress(builder: Notification.Builder, parsed: ParsedProgress) {
        val systemStyle = Notification.ProgressStyle()
            .setProgress(parsed.progress)
            .setProgressIndeterminate(parsed.indeterminate)
            .setStyledByProgress(parsed.styledByProgress)
            .setProgressSegments(parsed.segments.map { segment ->
                Notification.ProgressStyle.Segment(segment.length).apply {
                    segment.id?.let(::setId)
                    segment.color?.let(::setColor)
                    setSemanticStyle(segment.semantic)
                }
            })
            .setProgressPoints(parsed.points.map { point ->
                Notification.ProgressStyle.Point(point.position).apply {
                    point.id?.let(::setId)
                    point.color?.let(::setColor)
                    setSemanticStyle(point.semantic)
                }
            })
        parsed.startIcon?.let(systemStyle::setProgressStartIcon)
        parsed.endIcon?.let(systemStyle::setProgressEndIcon)
        parsed.trackerIcon?.let(systemStyle::setProgressTrackerIcon)
        builder.setStyle(systemStyle)
    }

    private fun applyTimer(builder: Notification.Builder, style: JSONObject) {
        requireFields(style, setOf("kind", "value"))
        builder.setCategory(Notification.CATEGORY_STATUS)
        applyClock(builder, requiredObject(style, "value"))
    }

    /** The header clock of a timer style or of a chronometer: down to its end, or up from its start. */
    private fun applyClock(builder: Notification.Builder, value: JSONObject) {
        requireFields(value, setOf("timer", "countsDown"))
        val (startedAt, endsAt) = timer(requiredObject(value, "timer"))
        val countsDown = requiredBoolean(value, "countsDown")
        builder.setWhen(if (countsDown) endsAt else startedAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(countsDown)
    }

    private fun applyMetric(builder: Notification.Builder, style: JSONObject) {
        requireFields(style, setOf("kind", "value"))
        val value = requiredObject(style, "value")
        requireFields(value, setOf("metrics"), setOf("criticalMetric"))
        val metrics = requiredArray(value, "metrics")
        if (metrics.length() !in 1..MAX_METRICS) invalid()
        val parsed = (0 until metrics.length()).map { parseMetric(metrics.getJSONObject(it)) }
        val critical = optionalInt(value, "criticalMetric", 0, metrics.length() - 1)
        builder.setCategory(Notification.CATEGORY_STATUS)
        if (sdk >= 37 && Build.VERSION.SDK_INT >= 37) applySystemMetric(builder, parsed, critical)
    }

    private fun parseMetric(value: JSONObject): ParsedMetric {
        requireFields(value, setOf("label", "value"), setOf("semanticStyle"))
        val label = requiredText(value, "label", 10)
        val metricValue = requiredObject(value, "value")
        requireFields(metricValue, setOf("kind", "value"))
        val parsedValue = when (requiredText(metricValue, "kind", 16)) {
            "integer" -> parseIntegerMetric(metricValue.get("value"))
            "decimal" -> parseDecimalMetric(metricValue.get("value"))
            "text" -> ParsedMetricValue.Text(requiredTextValue(metricValue.get("value"), 64))
            "stopwatch" -> ParsedMetricValue.Stopwatch(metricTime(metricValue, "startedAt"))
            "countdown" -> ParsedMetricValue.Countdown(metricTime(metricValue, "endsAt"))
            else -> invalid()
        }
        return ParsedMetric(label, parsedValue, optionalSemantic(value))
    }

    private fun parseIntegerMetric(raw: Any): ParsedMetricValue.Integer {
        val value = raw as? JSONObject ?: invalid()
        requireFields(value, setOf("value"), setOf("unit"))
        return ParsedMetricValue.Integer(requiredInt(value, "value", Int.MIN_VALUE, Int.MAX_VALUE), optionalText(value, "unit", 8))
    }

    private fun parseDecimalMetric(raw: Any): ParsedMetricValue.Decimal {
        val value = raw as? JSONObject ?: invalid()
        requireFields(value, setOf("value"), setOf("unit"))
        val number = requiredDouble(value, "value")
        if (!number.toFloat().isFinite()) invalid()
        return ParsedMetricValue.Decimal(number.toFloat(), optionalText(value, "unit", 8))
    }

    private fun metricTime(value: JSONObject, field: String): Long {
        val nested = value.get("value") as? JSONObject ?: invalid()
        requireFields(nested, setOf(field))
        return requiredLong(nested, field, 0, MAX_TIMESTAMP)
    }

    @SuppressLint("NewApi")
    private fun applySystemMetric(
        builder: Notification.Builder,
        parsed: List<ParsedMetric>,
        critical: Int?,
    ) {
        val systemStyle = Notification.MetricStyle()
        parsed.forEach { metric ->
            val value = when (val item = metric.value) {
                is ParsedMetricValue.Integer -> Notification.Metric.FixedInt(item.value, item.unit)
                is ParsedMetricValue.Decimal -> Notification.Metric.FixedFloat(item.value, item.unit)
                is ParsedMetricValue.Text -> Notification.Metric.FixedText(item.value)
                is ParsedMetricValue.Stopwatch -> Notification.Metric.TimeDifference.forStopwatch(
                    Instant.ofEpochMilli(item.startedAt),
                    Notification.Metric.TimeDifference.FORMAT_ADAPTIVE,
                )
                is ParsedMetricValue.Countdown -> Notification.Metric.TimeDifference.forTimer(
                    Instant.ofEpochMilli(item.endsAt),
                    Notification.Metric.TimeDifference.FORMAT_ADAPTIVE,
                )
            }
            systemStyle.addMetric(Notification.Metric(value, metric.label, metric.semantic))
        }
        critical?.let(systemStyle::setCriticalMetric)
        builder.setStyle(systemStyle)
    }

    private fun applyPresentationOptions(
        builder: Notification.Builder,
        activityId: String,
        presentation: JSONObject,
        title: String,
        status: String,
    ) {
        optionalText(presentation, "shortCriticalText", 7)?.let { text ->
            if (sdk >= 36 && Build.VERSION.SDK_INT >= 36) builder.setShortCriticalText(text)
        }
        optionalIcon(presentation, "largeIcon")?.let(builder::setLargeIcon)
        val actions = optionalArray(presentation, "actions") ?: JSONArray()
        applyActions(builder, activityId, actions)
        builder.setDeleteIntent(deleteIntent(activityId))
        optionalObject(presentation, "samsungNowBar")?.let { applySamsung(builder, it, title, status) }
    }

    private fun applyActions(builder: Notification.Builder, activityId: String, actions: JSONArray) {
        if (actions.length() > MAX_ACTIONS) invalid()
        val ids = mutableSetOf<String>()
        for (index in 0 until actions.length()) {
            val value = actions.getJSONObject(index)
            requireFields(value, setOf("id", "title"), setOf("icon", "authenticationRequired", "openUrl"))
            val id = requiredText(value, "id", 64)
            if (!ACTION_ID.matches(id) || !ids.add(id)) invalid()
            val title = requiredText(value, "title", 64)
            val icon = optionalIcon(value, "icon")
            val authentication = optionalBoolean(value, "authenticationRequired") ?: false
            // An action with a URL opens it; Android lets no receiver of a notification start an activity.
            val pending = optionalText(value, "openUrl", MAX_URL)?.let { url -> link(activityId, id, url) }
                ?: actionBroadcast(activityId, id)
            builder.addAction(
                Notification.Action.Builder(icon, title, pending).apply {
                    if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(authentication)
                }.build(),
            )
        }
    }

    private fun actionBroadcast(activityId: String, actionId: String): PendingIntent {
        val data = Uri.Builder().scheme("liveupdate").authority("action")
            .appendPath(activityId).appendPath(actionId).build()
        val intent = Intent(application, LiveUpdateActionReceiver::class.java)
            .setAction(LIVE_UPDATE_ACTION).setData(data)
            .putExtra(ACTIVITY_ID_EXTRA, activityId).putExtra(ACTION_ID_EXTRA, actionId)
        return PendingIntent.getBroadcast(
            application,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun deleteIntent(activityId: String): PendingIntent {
        val data = Uri.Builder().scheme("liveupdate").authority("dismiss").appendPath(activityId).build()
        val intent = Intent(application, LiveUpdateDismissReceiver::class.java)
            .setAction(LIVE_UPDATE_DISMISSED).setData(data)
            .putExtra(ACTIVITY_ID_EXTRA, activityId)
        return PendingIntent.getBroadcast(
            application,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun applySamsung(
        builder: Notification.Builder,
        value: JSONObject,
        defaultPrimary: String,
        defaultSecondary: String,
    ) {
        requireFields(value, emptySet(), setOf("primary", "secondary"))
        val title = optionalText(value, "primary", 120) ?: defaultPrimary
        val secondary = optionalText(value, "secondary", 240) ?: defaultSecondary
        if (!manufacturer.equals("samsung", ignoreCase = true)) return
        val extras = Bundle()
        SamsungNowBar.extras(sdk, title, secondary).forEach { (key, item) ->
            when {
                item is Int -> extras.putInt(key, item)
                SamsungNowBar.isCharSequence(key) -> extras.putCharSequence(key, item as String)
                else -> extras.putString(key, item as String)
            }
        }
        builder.addExtras(extras)
    }

    private fun validateCommonSnapshot(snapshot: JSONObject) {
        requiredLong(snapshot, "revision", 0, MAX_SAFE_INTEGER)
        requiredText(snapshot, "title", 120)
        requiredText(snapshot, "status", 240)
        if (!snapshot.isMissingOrNull("staleAt")) requiredLong(snapshot, "staleAt", 0, MAX_TIMESTAMP)
    }

    private fun timer(value: JSONObject): Pair<Long, Long> {
        requireFields(value, setOf("startedAt", "endsAt"))
        val startedAt = requiredLong(value, "startedAt", 0, MAX_TIMESTAMP)
        val endsAt = requiredLong(value, "endsAt", 0, MAX_TIMESTAMP)
        if (endsAt <= startedAt) invalid()
        return startedAt to endsAt
    }

    private fun visibility(value: String): Int = when (value) {
        "public" -> Notification.VISIBILITY_PUBLIC
        "private" -> Notification.VISIBILITY_PRIVATE
        "secret" -> Notification.VISIBILITY_SECRET
        else -> invalid()
    }

    private fun optionalIcon(value: JSONObject, field: String): Icon? =
        optionalObject(value, field)?.let(icons::resolve)

    private fun optionalSemantic(value: JSONObject): Int = when (optionalText(value, "semanticStyle", 16)) {
        null, "unspecified" -> Notification.SEMANTIC_STYLE_UNSPECIFIED
        "info" -> Notification.SEMANTIC_STYLE_INFO
        "safe" -> Notification.SEMANTIC_STYLE_SAFE
        "caution" -> Notification.SEMANTIC_STYLE_CAUTION
        "danger" -> Notification.SEMANTIC_STYLE_DANGER
        else -> invalid()
    }

    private fun optionalColor(value: JSONObject, field: String): Int? {
        if (value.isMissingOrNull(field)) return null
        return requiredLong(value, field, 0, 0xffff_ffffL).toInt()
    }

    private fun requiredObject(value: JSONObject, field: String): JSONObject =
        value.opt(field) as? JSONObject ?: invalid()

    private fun optionalObject(value: JSONObject, field: String): JSONObject? = when {
        value.isMissingOrNull(field) -> null
        else -> value.opt(field) as? JSONObject ?: invalid()
    }

    private fun requiredArray(value: JSONObject, field: String): JSONArray =
        value.opt(field) as? JSONArray ?: invalid()

    private fun optionalArray(value: JSONObject, field: String): JSONArray? = when {
        value.isMissingOrNull(field) -> null
        else -> value.opt(field) as? JSONArray ?: invalid()
    }

    private fun requiredText(value: JSONObject, field: String, maximum: Int): String =
        requiredTextValue(value.opt(field), maximum)

    private fun requiredTextValue(raw: Any?, maximum: Int): String {
        val text = raw as? String ?: invalid()
        if (!isValidContractText(text, maximum)) invalid()
        return text
    }

    private fun optionalText(value: JSONObject, field: String, maximum: Int): String? = when {
        value.isMissingOrNull(field) -> null
        else -> requiredText(value, field, maximum)
    }

    private fun requiredBoolean(value: JSONObject, field: String): Boolean =
        value.opt(field) as? Boolean ?: invalid()

    private fun optionalBoolean(value: JSONObject, field: String): Boolean? = when {
        value.isMissingOrNull(field) -> null
        else -> requiredBoolean(value, field)
    }

    private fun requiredInt(value: JSONObject, field: String, minimum: Int, maximum: Int): Int {
        val number = value.opt(field) as? Number ?: invalid()
        val long = number.toLong()
        if (number.toDouble() != long.toDouble() || long !in minimum.toLong()..maximum.toLong()) invalid()
        return long.toInt()
    }

    private fun optionalInt(value: JSONObject, field: String, minimum: Int, maximum: Int): Int? = when {
        value.isMissingOrNull(field) -> null
        else -> requiredInt(value, field, minimum, maximum)
    }

    private fun requiredLong(value: JSONObject, field: String, minimum: Long, maximum: Long): Long {
        val number = value.opt(field) as? Number ?: invalid()
        val long = number.toLong()
        if (number.toDouble() != long.toDouble() || long !in minimum..maximum) invalid()
        return long
    }

    private fun requiredDouble(value: JSONObject, field: String): Double {
        val number = value.opt(field) as? Number ?: invalid()
        return number.toDouble().also { if (!it.isFinite()) invalid() }
    }

    private fun requireFields(value: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val fields = value.keys().asSequence().toSet()
        if (!fields.containsAll(required) || fields.any { it !in required && it !in optional }) invalid()
    }

    private fun JSONObject.isMissingOrNull(field: String) = !has(field) || isNull(field)

    private fun invalid(): Nothing = throw AdapterFailure("INVALID_INPUT", INVALID_MESSAGE)
}

private data class ParsedProgress(
    val progress: Int,
    val maximum: Int,
    val indeterminate: Boolean,
    val styledByProgress: Boolean,
    val segments: List<ParsedSegment>,
    val points: List<ParsedPoint>,
    val startIcon: Icon?,
    val endIcon: Icon?,
    val trackerIcon: Icon?,
)

private data class ParsedSegment(val length: Int, val id: Int?, val color: Int?, val semantic: Int)
private data class ParsedPoint(val position: Int, val id: Int?, val color: Int?, val semantic: Int)
private data class ParsedMetric(val label: String, val value: ParsedMetricValue, val semantic: Int)

private sealed interface ParsedMetricValue {
    data class Integer(val value: Int, val unit: String?) : ParsedMetricValue
    data class Decimal(val value: Float, val unit: String?) : ParsedMetricValue
    data class Text(val value: String) : ParsedMetricValue
    data class Stopwatch(val startedAt: Long) : ParsedMetricValue
    data class Countdown(val endsAt: Long) : ParsedMetricValue
}
