package io.github.eedaegon.liveupdate

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
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
import java.time.Instant

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

    // LU-V2-PROGRESS-2
    @Test
    fun progressStyleOnApi36IsPostedWithoutSemanticStyles() {
        assertTrue(Build.VERSION.SDK_INT >= 36)
        // A device of API 36, or this one as API 36: the semantic style of a segment and of a point is a
        // method of API 37, which API 36 does not have.
        val api36 = AndroidAdapter(context, sdk = 36)
        val progress = JSONObject()
            .put("progress", 250)
            .put("segments", JSONArray()
                .put(segment(400, 1, 0xff00ff00.toInt(), "safe"))
                .put(segment(600, null, null, null)))
            .put("points", JSONArray().put(point(400, 10, 0xff2196f3.toInt(), "info")))
        val style = JSONObject().put("kind", "progress").put("value", progress)

        api36.post(v2Record("api36-progress", "progress", v2Snapshot(style)))

        val notification = Device.waitForShown("api36-progress")
        val restored = Notification.Builder.recoverBuilder(context, notification).style
            as Notification.ProgressStyle
        assertEquals(250, restored.progress)
        assertEquals(1000, restored.progressMax)
        assertEquals(listOf(400, 600), restored.progressSegments.map { it.length })
        assertEquals(1, restored.progressSegments[0].id)
        assertEquals(0xff00ff00.toInt(), restored.progressSegments[0].color)
        assertEquals(listOf(400), restored.progressPoints.map { it.position })
        assertEquals(10, restored.progressPoints.single().id)
        assertEquals(0xff2196f3.toInt(), restored.progressPoints.single().color)
        if (Build.VERSION.SDK_INT >= 37) {
            assertEquals(Notification.SEMANTIC_STYLE_UNSPECIFIED, restored.progressSegments[0].semanticStyle)
            assertEquals(Notification.SEMANTIC_STYLE_UNSPECIFIED, restored.progressPoints.single().semanticStyle)
        }
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

    // LU-V2-METRIC-2
    @Test
    fun metricStyleCountsFromAStopwatchStartAndToACountdownEnd() {
        assertTrue(Build.VERSION.SDK_INT >= 37)
        val startedAt = System.currentTimeMillis() - 60_000
        val endsAt = startedAt + 660_000
        fun times(started: String, ends: String) = JSONObject().put("kind", "metric").put("value", JSONObject()
            .put("metrics", JSONArray()
                .put(metric("Elapsed", "stopwatch", JSONObject().put(started, startedAt), null))
                .put(metric("Left", "countdown", JSONObject().put(ends, endsAt), null))))

        adapter.post(v2Record("times", "metric", v2Snapshot(times("startedAt", "endsAt"))))

        val restored = Notification.Builder.recoverBuilder(context, Device.waitForShown("times")).style
            as Notification.MetricStyle
        val stopwatch = restored.metrics[0].value as Notification.Metric.TimeDifference
        assertTrue(stopwatch.isStopwatch)
        assertEquals(Instant.ofEpochMilli(startedAt), stopwatch.zeroTime)
        val countdown = restored.metrics[1].value as Notification.Metric.TimeDifference
        assertTrue(countdown.isTimer)
        assertEquals(Instant.ofEpochMilli(endsAt), countdown.zeroTime)

        // The adapter reads the contract's names only: the lifecycle writes them.
        listOf("started_at" to "endsAt", "startedAt" to "ends_at").forEachIndexed { index, (started, ends) ->
            val refused = v2Record("times-$index", "metric", v2Snapshot(times(started, ends)))
            assertEquals("INVALID_INPUT", failureCode { adapter.post(refused) })
        }
    }

    // LU-V2-ADAPTER-2
    @Test
    fun standardBigTextTimerAndLowerApiFallbackUseOnlySystemTemplates() {
        val standard = JSONObject().put("kind", "standard")
        adapter.post(v2Record("standard", "standard", v2Snapshot(standard)))
        assertNull(Device.styleOf(Device.waitForShown("standard")))

        val big = JSONObject().put("kind", "bigText").put("value", JSONObject()
            .put("text", "A detailed delivery explanation")
            .put("summary", "Delivery summary"))
        adapter.post(v2Record("big", "bigText", v2Snapshot(big)))
        val bigNotification = Device.waitForShown("big")
        assertEquals(Device.BIG_TEXT_STYLE, Device.styleOf(bigNotification))
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
        assertNull(Device.styleOf(oldProgress))
        assertEquals(100, oldProgress.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(25, oldProgress.extras.getInt(Notification.EXTRA_PROGRESS))

        older.post(v2Record("old-metric", "metric", v2Snapshot(JSONObject()
            .put("kind", "metric").put("value", JSONObject()
                .put("metrics", JSONArray().put(metric("Steps", "integer", JSONObject().put("value", 42), null)))))))
        val oldMetric = Device.waitForShown("old-metric")
        assertEquals(Device.BIG_TEXT_STYLE, Device.styleOf(oldMetric))
        assertEquals("Preparing\nSteps 42", oldMetric.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
    }

    // LU-V2-METRIC-4
    @Test
    fun belowApi37AMetricStyleShowsItsNumbersAndTextsAsLinesAndItsFirstClockInTheHeader() {
        val now = System.currentTimeMillis()
        val mixed = metrics(
            metric("Steps", "integer", JSONObject().put("value", 42).put("unit", "st"), "safe"),
            metric("Left", "countdown", JSONObject().put("endsAt", now + 600_000), null),
            metric("State", "text", "Ready", null),
        ).apply { getJSONObject("value").put("criticalMetric", 2) }
        for (sdk in listOf(26, 34, 36)) {
            AndroidAdapter(context, sdk = sdk).post(v2Record("lines-$sdk", "metric", v2Snapshot(mixed)))
            val shown = Device.waitForShown("lines-$sdk")
            assertEquals(Device.BIG_TEXT_STYLE, Device.styleOf(shown))
            assertEquals(
                "Preparing\nSteps 42 st\nState Ready",
                shown.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString(),
            )
            assertEquals("Preparing", shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(Notification.CATEGORY_STATUS, shown.category)
            // The countdown has no line: it runs in the header, down to its end.
            assertEquals(now + 600_000, shown.`when`)
            assertTrue(shown.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertTrue(shown.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        }

        // Clocks alone leave no text to expand. The first one runs, a stopwatch up from its start, and the
        // second has no place.
        val older = AndroidAdapter(context, sdk = 36)
        val clocks = metrics(
            metric("Elapsed", "stopwatch", JSONObject().put("startedAt", now - 60_000), null),
            metric("Left", "countdown", JSONObject().put("endsAt", now + 600_000), null),
        )
        older.post(v2Record("clocks", "metric", v2Snapshot(clocks)))
        val clock = Device.waitForShown("clocks")
        assertNull(Device.styleOf(clock))
        assertEquals(now - 60_000, clock.`when`)
        assertTrue(clock.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertFalse(clock.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))

        // The presentation's own chronometer is the clock, whatever the metrics have.
        val own = v2Snapshot(mixed, chronometer = chronometer(now - 5_000, now + 5_000, false))
        older.post(v2Record("own-clock", "metric", own))
        val ownClock = Device.waitForShown("own-clock")
        assertEquals(Device.BIG_TEXT_STYLE, Device.styleOf(ownClock))
        assertEquals(now - 5_000, ownClock.`when`)
        assertFalse(ownClock.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))

        // Without a clock among them the header shows none.
        older.post(v2Record("no-clock", "metric", v2Snapshot(metrics(metric("State", "text", "Ready", null)))))
        assertFalse(Device.waitForShown("no-clock").extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }

    // LU-V2-METRIC-4, LU-ADAPTER-10
    @Test
    fun aMetricsClockThatAndroidWouldNotPostIsLeftOutAndTheMetricsArePosted() {
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L
        fun old(age: Long) = v2Snapshot(metrics(
            metric("Steps", "integer", JSONObject().put("value", 42), null),
            metric("Elapsed", "stopwatch", JSONObject().put("startedAt", now - age), null),
        ))
        // Built and not posted where the clock is that old: the device's own Android would ignore the post.
        fun clockOf(sdk: Int, age: Long): Boolean = AndroidAdapter(context, sdk = sdk)
            .build(v2Record("built", "metric", old(age))).extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)

        assertTrue(clockOf(34, 15 * day))
        assertTrue(clockOf(35, 13 * day))
        assertFalse(clockOf(35, 15 * day))
        assertFalse(clockOf(36, 14 * day))

        // From API 35 a header clock of that age is refused. A metric's is not: it is left out.
        val from35 = AndroidAdapter(context, sdk = 35)
        from35.post(v2Record("old-metric-clock", "metric", old(15 * day)))
        val shown = Device.waitForShown("old-metric-clock")
        assertFalse(shown.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("Preparing\nSteps 42", shown.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        val refused = v2Snapshot(metrics(metric("Steps", "integer", JSONObject().put("value", 42), null)),
            chronometer = chronometer(now - 15 * day, now + day, false))
        assertEquals("INVALID_INPUT", failureCode { from35.post(v2Record("old-own-clock", "metric", refused)) })
    }

    // LU-V2-CHRONO-2
    @Test
    fun aChronometerCountsInTheHeaderBesideAStyle() {
        assertTrue(Build.VERSION.SDK_INT >= 37)
        val now = System.currentTimeMillis()
        val standard = JSONObject().put("kind", "standard")

        val down = chronometer(now - 60_000, now + 600_000, countsDown = true)
        adapter.post(v2Record("clock-progress", "progress", v2Snapshot(progressStyle(40), chronometer = down)))
        val progress = Device.waitForShown("clock-progress")
        assertEquals(now + 600_000, progress.`when`)
        assertTrue(progress.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertTrue(progress.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(progress.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        val restored = Notification.Builder.recoverBuilder(context, progress).style as Notification.ProgressStyle
        assertEquals(40, restored.progress)
        assertTrue(progress.hasPromotableCharacteristics())

        val up = chronometer(now - 60_000, now + 600_000, countsDown = false)
        adapter.post(v2Record("clock-standard", "standard", v2Snapshot(standard, chronometer = up)))
        val counting = Device.waitForShown("clock-standard")
        assertEquals(now - 60_000, counting.`when`)
        assertTrue(counting.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertFalse(counting.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))

        adapter.post(v2Record("no-clock", "standard", v2Snapshot(standard)))
        val without = Device.waitForShown("no-clock")
        assertFalse(without.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertFalse(without.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }

    // LU-V2-CHRONO-2
    @Test
    fun aChronometerTheContractRefusesPostsNothing() {
        val now = System.currentTimeMillis()
        val standard = JSONObject().put("kind", "standard")
        val clock = chronometer(now, now + 60_000, countsDown = true)
        val timer = JSONObject().put("kind", "timer").put("value", JSONObject(clock.toString()))
        val invalid = listOf(
            "timer" to v2Snapshot(timer, chronometer = clock),
            "standard" to v2Snapshot(standard, chronometer = chronometer(now, now, countsDown = true)),
            "standard" to v2Snapshot(standard, chronometer = JSONObject(clock.toString()).put("countsDown", "yes")),
            "standard" to v2Snapshot(standard, chronometer = JSONObject().put("timer", clock.getJSONObject("timer"))),
        )
        invalid.forEachIndexed { index, (template, snapshot) ->
            assertEquals(
                "clock-$index",
                "INVALID_INPUT",
                failureCode { adapter.post(v2Record("clock-$index", template, snapshot)) },
            )
        }
        assertTrue(Device.manager.activeNotifications.isEmpty())
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
        notification.actions.forEach { Device.assertImmutable(it.actionIntent) }
        // Android asks from API 31; before it the AAR withholds the tap of a locked device.
        if (Build.VERSION.SDK_INT >= 31) assertTrue(notification.actions[1].isAuthenticationRequired)
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

    // LU-V2-ACTION-3
    @Test
    fun anActionThatRequiresAuthenticationWaitsForTheUnlockBelowApi31() {
        val record = v2Record("guarded", "standard", v2Snapshot(
            JSONObject().put("kind", "standard"),
            actions = JSONArray().put(action("pay", "Pay", true)).put(action("open", "Open", false)),
        ))
        adapter.post(record)
        val notification = Device.waitForShown("guarded")
        // Each intent says whether its action requires authentication.
        assertTrue(sent(notification.actions[0].actionIntent).getBooleanExtra("authenticationRequired", false))
        assertFalse(sent(notification.actions[1].actionIntent).getBooleanExtra("authenticationRequired", true))
        Device.waitUntil("both taps are queued") { EventStore(context).size() == 2 }

        // A tap of [action] on a device of [sdk] that is [locked] or not, with what its intent says or, as an
        // intent of an earlier version, nothing: the events stored and the listener's calls.
        val heard = java.util.concurrent.atomic.AtomicInteger()
        LiveUpdateEvents.setListener { heard.incrementAndGet() }
        fun taken(sdk: Int, locked: Boolean, action: String = "pay", says: Boolean? = null): Pair<Int, Int> {
            adapter.drainEvents()
            heard.set(0)
            val tap = Intent(LIVE_UPDATE_ACTION).putExtra(ACTIVITY_ID_EXTRA, "guarded").putExtra(ACTION_ID_EXTRA, action)
            if (says != null) tap.putExtra(AUTHENTICATION_EXTRA, says)
            ActionTap(sdk) { locked }.take(context, tap)
            return EventStore(context).size() to heard.get()
        }
        val preferences = context.getSharedPreferences(Device.PREFERENCES, android.content.Context.MODE_PRIVATE)
        try {
            // Without a receipt the intent alone says it.
            assertEquals(0 to 0, taken(sdk = 30, locked = true, says = true))
            assertEquals(1 to 1, taken(sdk = 30, locked = false, says = true))
            assertEquals(1 to 1, taken(sdk = 31, locked = true, says = true))
            assertEquals(1 to 1, taken(sdk = 30, locked = true, says = false))
            assertEquals(1 to 1, taken(sdk = 30, locked = true))

            // The stored receipt says it for an intent that does not.
            preferences.edit().putString("records", JSONArray().put(record).toString()).commit()
            assertEquals(0 to 0, taken(sdk = 30, locked = true))
            assertEquals(1 to 1, taken(sdk = 30, locked = false))
            assertEquals(1 to 1, taken(sdk = 31, locked = true))
            assertEquals(1 to 1, taken(sdk = 30, locked = true, action = "open"))
            assertEquals(1 to 1, taken(sdk = 30, locked = true, action = "other"))
            // Either one is enough, whatever the other says.
            assertEquals(0 to 0, taken(sdk = 30, locked = true, says = false))
            assertEquals(0 to 0, taken(sdk = 30, locked = true, action = "open", says = true))

            // Receipts that cannot be read protect the action, and only while the device is locked.
            preferences.edit().putString("records", "not json").commit()
            assertEquals(0 to 0, taken(sdk = 30, locked = true, action = "open"))
            assertEquals(1 to 1, taken(sdk = 30, locked = false, action = "open"))
            assertEquals(1 to 1, taken(sdk = 31, locked = true, action = "open"))
        } finally {
            LiveUpdateEvents.setListener(null)
        }
        // The device's own answer is the keyguard's.
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        assertEquals(keyguard.isDeviceLocked, deviceLocked(context))
    }

    // LU-V2-EVENT-6
    @Test
    fun theListenerHearsAnActionAndADismissalOnceEachIsStored() {
        val queued = java.util.Collections.synchronizedList(mutableListOf<Int>())
        LiveUpdateEvents.setListener { queued += EventStore(context).size() }
        try {
            adapter.post(v2Record("listened", "standard", v2Snapshot(
                JSONObject().put("kind", "standard"),
                actions = JSONArray().put(action("confirm", "Confirm", false)),
            )))
            val notification = Device.waitForShown("listened")

            notification.actions[0].actionIntent.send()
            Device.waitUntil("the listener heard the action") { queued.size == 1 }
            notification.deleteIntent.send()
            Device.waitUntil("the listener heard the dismissal") { queued.size == 2 }

            // Each call came after its event was stored.
            assertEquals(listOf(1, 2), queued.toList())
            assertEquals(2, adapter.drainEvents().getJSONArray("events").length())
        } finally {
            LiveUpdateEvents.setListener(null)
        }
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

    private fun metrics(vararg values: JSONObject) = JSONObject().put("kind", "metric")
        .put("value", JSONObject().put("metrics", JSONArray().apply { values.forEach { put(it) } }))

    private fun metric(label: String, kind: String, value: Any, semantic: String?) = JSONObject()
        .put("label", label)
        .put("value", JSONObject().put("kind", kind).put("value", value))
        .apply { if (semantic != null) put("semanticStyle", semantic) }

    private fun action(id: String, title: String, authentication: Boolean) = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("authenticationRequired", authentication)

    private fun chronometer(startedAt: Long, endsAt: Long, countsDown: Boolean) = JSONObject()
        .put("timer", JSONObject().put("startedAt", startedAt).put("endsAt", endsAt))
        .put("countsDown", countsDown)

    private fun samePendingIntent(first: PendingIntent, second: PendingIntent): Boolean = first == second

    /** Sends [intent] and answers the intent that Android delivered for it. */
    private fun sent(intent: PendingIntent): Intent {
        val delivered = java.util.concurrent.LinkedBlockingQueue<Intent>()
        intent.send(context, 0, null, { _, sent, _, _, _ -> delivered.put(sent) }, null)
        return checkNotNull(delivered.poll(10, java.util.concurrent.TimeUnit.SECONDS)) { "the intent was not delivered" }
    }

    private fun failureCode(action: () -> Unit): String =
        org.junit.Assert.assertThrows(AdapterFailure::class.java) { action() }.code
}
