package io.github.eedaegon.liveupdate

import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.eedaegon.liveupdate.Device.appFileIcon
import io.github.eedaegon.liveupdate.Device.context
import io.github.eedaegon.liveupdate.Device.resourceIcon
import io.github.eedaegon.liveupdate.Device.v2Record
import io.github.eedaegon.liveupdate.Device.v2Snapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class RichPresentationInstrumentationTest {
    private val adapter = AndroidAdapter(context)

    @Before
    fun prepare() = Device.prepare()

    @After
    fun clear() {
        File(context.filesDir, "liveupdate-test").deleteRecursively()
        Device.clear()
    }

    // LU-V2-PROGRESS-2, LU-V2-ADAPTER-1
    @Test
    fun progressStyleCarriesSegmentsPointsIconsAndChipText() {
        assertTrue(Build.VERSION.SDK_INT >= 37)
        val progress = JSONObject()
            .put("progress", 60)
            .put("indeterminate", false)
            .put("styledByProgress", true)
            .put("segments", JSONArray()
                .put(segment(40, 1, 0xff00ff00.toInt(), "safe"))
                .put(segment(60, 2, 0xffffa000.toInt(), "caution")))
            .put("points", JSONArray()
                .put(point(40, 10, 0xff2196f3.toInt(), "info")))
            .put("startIcon", resourceIcon("liveupdate_status"))
            .put("endIcon", resourceIcon("liveupdate_status"))
            .put("trackerIcon", resourceIcon("liveupdate_status"))
        val style = JSONObject().put("kind", "progress").put("value", progress)
        val record = v2Record(
            "rich-progress",
            "progress",
            v2Snapshot(style, shortCriticalText = "60%"),
        )

        adapter.post(record)

        val notification = Device.waitForShown("rich-progress")
        val restored = Notification.Builder.recoverBuilder(context, notification).style
            as Notification.ProgressStyle
        assertEquals(60, restored.progress)
        assertEquals(100, restored.progressMax)
        assertTrue(restored.isStyledByProgress)
        assertFalse(restored.isProgressIndeterminate)
        assertEquals(listOf(40, 60), restored.progressSegments.map { it.length })
        assertEquals(listOf(1, 2), restored.progressSegments.map { it.id })
        assertEquals(listOf(40), restored.progressPoints.map { it.position })
        assertEquals(10, restored.progressPoints.single().id)
        assertEquals(R.drawable.liveupdate_status, restored.progressTrackerIcon!!.resId)
        assertEquals("60%", notification.shortCriticalText)
        assertTrue(notification.hasPromotableCharacteristics())
    }

    // LU-V2-METRIC-2
    @Test
    fun metricStyleCarriesThreeTypedMetricsAndCriticalSelection() {
        assertTrue(Build.VERSION.SDK_INT >= 37)
        val metrics = JSONArray()
            .put(metric("Steps", "integer", JSONObject().put("value", 1979).put("unit", "st"), "safe"))
            .put(metric("Speed", "decimal", JSONObject().put("value", 12.5).put("unit", "km/h"), "info"))
            .put(metric("State", "text", "Ready", "caution"))
        val style = JSONObject().put("kind", "metric").put("value", JSONObject()
            .put("metrics", metrics)
            .put("criticalMetric", 1))

        adapter.post(v2Record("metrics", "metric", v2Snapshot(style)))

        val notification = Device.waitForShown("metrics")
        val restored = Notification.Builder.recoverBuilder(context, notification).style
            as Notification.MetricStyle
        assertEquals(3, restored.metrics.size)
        assertEquals("Steps", restored.metrics[0].label.toString())
        assertEquals(1979, (restored.metrics[0].value as Notification.Metric.FixedInt).value)
        assertEquals(12.5f, (restored.metrics[1].value as Notification.Metric.FixedFloat).value)
        assertEquals("Ready", (restored.metrics[2].value as Notification.Metric.FixedText).value.toString())
        assertEquals(restored.metrics[1], restored.criticalMetric)
    }

    // LU-V2-ADAPTER-2
    @Test
    fun standardBigTextTimerAndLowerApiFallbackUseOnlySystemTemplates() {
        val standard = JSONObject().put("kind", "standard")
        adapter.post(v2Record("standard", "standard", v2Snapshot(standard)))
        assertNull(Notification.Builder.recoverBuilder(context, Device.waitForShown("standard")).style)

        val big = JSONObject().put("kind", "bigText").put("value", JSONObject()
            .put("text", "A detailed delivery explanation")
            .put("summary", "Delivery summary"))
        adapter.post(v2Record("big", "bigText", v2Snapshot(big)))
        val bigNotification = Device.waitForShown("big")
        assertTrue(Notification.Builder.recoverBuilder(context, bigNotification).style is Notification.BigTextStyle)
        assertEquals("A detailed delivery explanation", bigNotification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertEquals("Delivery summary", bigNotification.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))

        val now = System.currentTimeMillis()
        val timer = JSONObject().put("kind", "timer").put("value", JSONObject()
            .put("timer", JSONObject().put("startedAt", now - 60_000).put("endsAt", now + 600_000))
            .put("countsDown", false))
        adapter.post(v2Record("timer-up", "timer", v2Snapshot(timer)))
        val timerNotification = Device.waitForShown("timer-up")
        assertEquals(now - 60_000, timerNotification.`when`)
        assertFalse(timerNotification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))

        val older = AndroidAdapter(context, sdk = 35)
        older.post(v2Record("old-progress", "progress", v2Snapshot(progressStyle(25))))
        val oldProgress = Device.waitForShown("old-progress")
        assertFalse(Notification.Builder.recoverBuilder(context, oldProgress).style is Notification.ProgressStyle)
        assertEquals(100, oldProgress.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(25, oldProgress.extras.getInt(Notification.EXTRA_PROGRESS))

        older.post(v2Record("old-metric", "metric", v2Snapshot(JSONObject()
            .put("kind", "metric").put("value", JSONObject()
                .put("metrics", JSONArray().put(metric("Steps", "integer", JSONObject().put("value", 42), null)))))))
        assertFalse(Notification.Builder.recoverBuilder(context, Device.waitForShown("old-metric")).style is Notification.MetricStyle)
    }

    // LU-V2-ACTION-2, LU-V2-EVENT-4
    @Test
    fun immutableUniqueActionsAndDismissalsDrainAsSequencedEvents() {
        val actions = JSONArray()
            .put(action("confirm", "Confirm", false))
            .put(action("cancel", "Cancel", true))
        val record = v2Record("events", "standard", v2Snapshot(
            JSONObject().put("kind", "standard"),
            actions = actions,
        ))
        adapter.post(record)

        val notification = Device.waitForShown("events")
        assertEquals(2, notification.actions.size)
        assertTrue(notification.actions.all { it.actionIntent.isImmutable })
        assertTrue(notification.actions[1].isAuthenticationRequired)
        assertNotNull(notification.deleteIntent)
        assertFalse(samePendingIntent(notification.actions[0].actionIntent, notification.actions[1].actionIntent))

        notification.actions[0].actionIntent.send()
        notification.deleteIntent.send()
        Device.waitUntil("two events are queued") { EventStore(context).size() == 2 }
        val events = adapter.drainEvents().getJSONArray("events")
        assertEquals("action", events.getJSONObject(0).getJSONObject("kind").getString("kind"))
        assertEquals("confirm", events.getJSONObject(0).getJSONObject("kind").getString("actionId"))
        assertEquals("dismissed", events.getJSONObject(1).getJSONObject("kind").getString("kind"))
        assertTrue(events.getJSONObject(0).getLong("sequence") < events.getJSONObject(1).getLong("sequence"))
        assertEquals(0, adapter.drainEvents().getJSONArray("events").length())
    }

    // LU-V2-EVENT-5
    @Test
    fun eventQueueIsDurableBoundedAndReportsDroppedEntries() {
        val first = EventStore(context)
        repeat(130) { index -> first.appendAction("activity", "action$index") }

        val batch = EventStore(context).drain()

        assertEquals(128, batch.getJSONArray("events").length())
        assertEquals(2, batch.getInt("dropped"))
        assertEquals("action2", batch.getJSONArray("events").getJSONObject(0)
            .getJSONObject("kind").getString("actionId"))
        assertEquals(0, first.drain().getJSONArray("events").length())

        context.getSharedPreferences(Device.PREFERENCES, android.content.Context.MODE_PRIVATE)
            .edit().putString("events.v2", "[{\"sequence\":1}]").commit()
        assertEquals("NATIVE_ERROR", failureCode { first.drain() })
    }

    // LU-VALID-10, LU-V2-EVENT-1
    @Test
    fun eventActivityIdentifierLimitsCountUnicodeCodePoints() {
        val maximum = "😀".repeat(256)
        val store = EventStore(context)

        store.appendDismissed(maximum)

        assertEquals(maximum, store.drain().getJSONArray("events")
            .getJSONObject(0).getString("activityId"))
        assertEquals("INVALID_INPUT", failureCode { store.appendDismissed(maximum + "😀") })
    }

    // LU-V2-ICON-2
    @Test
    fun appPrivateImagesWorkAndTraversalOrOversizedFilesFailClosed() {
        val directory = File(context.filesDir, "liveupdate-test").apply { mkdirs() }
        val image = File(directory, "large.png")
        image.outputStream().use { output ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        val large = appFileIcon("liveupdate-test/large.png")
        adapter.post(v2Record("image", "standard", v2Snapshot(
            JSONObject().put("kind", "standard"),
            largeIcon = large,
        )))
        assertNotNull(Device.waitForShown("image").getLargeIcon())

        val oversized = File(directory, "oversized.bin")
        RandomAccessFile(oversized, "rw").use { it.setLength(1_048_577) }

        val invalid = listOf(
            appFileIcon("../escape.png"),
            appFileIcon("/absolute.png"),
            appFileIcon("liveupdate-test/oversized.bin"),
            resourceIcon("missing_resource"),
        )
        invalid.forEachIndexed { index, source ->
            val record = v2Record("bad-image-$index", "standard", v2Snapshot(
                JSONObject().put("kind", "standard"),
                largeIcon = source,
            ))
            assertEquals("INVALID_INPUT", failureCode { adapter.post(record) })
        }
    }

    // LU-V2-SAMSUNG-2
    @Test
    fun samsungNowBarCapabilityAndExtrasAreVendorGated() {
        val nonSamsungAdapter = AndroidAdapter(context, manufacturer = "google")
        assertFalse(nonSamsungAdapter.capabilities()
            .getJSONObject("presentation").getBoolean("samsungNowBar"))
        val style = JSONObject().put("kind", "standard")
        nonSamsungAdapter.post(v2Record("plain-vendor", "standard", v2Snapshot(style)))
        val plain = Device.waitForShown("plain-vendor").extras
        assertFalse(plain.containsKey("android.ongoingActivityNoti.style"))

        val samsungAdapter = AndroidAdapter(context, manufacturer = "samsung")
        assertTrue(samsungAdapter.capabilities()
            .getJSONObject("presentation").getBoolean("samsungNowBar"))
        val samsung = JSONObject().put("primary", "Order").put("secondary", "Preparing")
        samsungAdapter.post(v2Record("samsung", "standard", v2Snapshot(style, samsungNowBar = samsung)))
        val extras = Device.waitForShown("samsung").extras
        assertEquals(1, extras.getInt("android.ongoingActivityNoti.style"))
        assertEquals("Order", extras.getCharSequence("android.ongoingActivityNoti.primaryInfo"))
        assertEquals("Preparing", extras.getCharSequence("android.ongoingActivityNoti.secondaryInfo"))
        assertEquals("Order", extras.getString("android.ongoingActivityNoti.nowbarPrimaryInfo"))
    }

    // LU-V2-VALID-2
    @Test
    fun malformedRichModelsAreRejectedBeforePosting() {
        val base = v2Snapshot(JSONObject().put("kind", "standard"))
        val duplicateActions = JSONArray()
            .put(action("same", "One", false))
            .put(action("same", "Two", false))
        val tooManyActions = JSONArray().apply {
            repeat(4) { put(action("action$it", "Action $it", false)) }
        }
        val invalid = listOf(
            v2Snapshot(JSONObject().put("kind", "unknown")),
            v2Snapshot(JSONObject().put("kind", "progress").put("value", JSONObject()
                .put("progress", 101).put("indeterminate", false).put("styledByProgress", false)
                .put("segments", JSONArray().put(segment(100, null, null, null)))
                .put("points", JSONArray()))),
            v2Snapshot(JSONObject().put("kind", "progress").put("value", JSONObject()
                .put("progress", 1).put("indeterminate", false).put("styledByProgress", false)
                .put("segments", JSONArray().put(segment(0, null, null, null)))
                .put("points", JSONArray()))),
            v2Snapshot(JSONObject().put("kind", "metric").put("value", JSONObject()
                .put("metrics", JSONArray().put(metric("Steps", "integer", JSONObject().put("value", 1), null)))
                .put("criticalMetric", 1))),
            v2Snapshot(JSONObject().put("kind", "standard"), actions = duplicateActions),
            v2Snapshot(JSONObject().put("kind", "standard"), actions = tooManyActions),
            v2Snapshot(JSONObject().put("kind", "standard"), shortCriticalText = "12345678"),
            JSONObject(base.toString()).put("progress", 0.5),
        )
        invalid.forEachIndexed { index, snapshot ->
            val template = snapshot.optJSONObject("presentation")?.optJSONObject("style")
                ?.optString("kind")?.takeIf { it in setOf("standard", "bigText", "progress", "timer", "metric") }
                ?: "standard"
            assertEquals(
                "invalid-$index",
                "INVALID_INPUT",
                failureCode { adapter.post(v2Record("invalid-$index", template, snapshot)) },
            )
        }
        assertTrue(Device.manager.activeNotifications.isEmpty())
    }

    private fun progressStyle(value: Int) = JSONObject().put("kind", "progress").put("value", JSONObject()
        .put("progress", value)
        .put("indeterminate", false)
        .put("styledByProgress", false)
        .put("segments", JSONArray().put(segment(100, null, null, null)))
        .put("points", JSONArray()))

    private fun segment(length: Int, id: Int?, color: Int?, semantic: String?) = JSONObject()
        .put("length", length)
        .apply {
            if (id != null) put("id", id)
            if (color != null) put("color", color.toLong() and 0xffffffffL)
            if (semantic != null) put("semanticStyle", semantic)
        }

    private fun point(position: Int, id: Int?, color: Int?, semantic: String?) = JSONObject()
        .put("position", position)
        .apply {
            if (id != null) put("id", id)
            if (color != null) put("color", color.toLong() and 0xffffffffL)
            if (semantic != null) put("semanticStyle", semantic)
        }

    private fun metric(label: String, kind: String, value: Any, semantic: String?) = JSONObject()
        .put("label", label)
        .put("value", JSONObject().put("kind", kind).put("value", value))
        .apply { if (semantic != null) put("semanticStyle", semantic) }

    private fun action(id: String, title: String, authentication: Boolean) = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("authenticationRequired", authentication)

    private fun samePendingIntent(first: PendingIntent, second: PendingIntent): Boolean = first == second

    private fun failureCode(action: () -> Unit): String =
        org.junit.Assert.assertThrows(AdapterFailure::class.java) { action() }.code
}
