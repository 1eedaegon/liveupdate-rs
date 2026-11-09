package io.github.eedaegon.liveupdate

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.eedaegon.liveupdate.Device.context
import io.github.eedaegon.liveupdate.Device.manager
import io.github.eedaegon.liveupdate.Device.record
import io.github.eedaegon.liveupdate.Device.snapshot
import io.github.eedaegon.liveupdate.Device.timer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The managed adapter's notifications, observations and storage on the device. */
@RunWith(AndroidJUnit4::class)
class AndroidAdapterInstrumentationTest {
    private val adapter = AndroidAdapter(context)

    @Before
    fun prepare() = Device.prepare()

    @After
    fun clear() = Device.clear()

    // LU-CAP-3
    @Test
    fun capabilitiesCreateTheLowImportanceChannel() {
        manager.deleteNotificationChannel(Device.CHANNEL_ID)
        adapter.capabilities()

        val channel = manager.getNotificationChannel(Device.CHANNEL_ID)!!
        assertEquals("Live updates", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        adapter.capabilities()
        assertEquals(1, manager.notificationChannels.count { it.id == Device.CHANNEL_ID })
    }

    // LU-ADAPTER-1, LU-ADAPTER-2, LU-ADAPTER-4
    @Test
    fun aProgressRecordPostsAnOngoingSystemTemplate() {
        adapter.post(record("progress", snapshot(progress = 0.4567), visibility = "private"))

        val notification = Device.waitForShown("progress")
        val extras = notification.extras
        assertEquals(Device.CHANNEL_ID, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.CATEGORY_PROGRESS, notification.category)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(R.drawable.liveupdate_status, notification.smallIcon.resId)
        assertEquals("Mochi's snack delivery", extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Preparing", extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(1000, extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(457, extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertFalse(extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertFalse(extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        val intent = notification.contentIntent!!
        assertTrue(intent.isImmutable)
        assertEquals(context.packageName, intent.creatorPackage)
        val stored = JSONObject(extras.getString("io.github.1eedaegon.liveupdate.record")!!)
        assertEquals("progress", stored.getString("id"))
    }

    // LU-ADAPTER-3
    @Test
    fun aTimerRecordPostsACountdownToItsEnd() {
        val endsAt = System.currentTimeMillis() + 600_000
        val countdown = snapshot(progress = null).put("timer", timer(endsAt - 1_200_000, endsAt))
        adapter.post(record("timer", countdown, visibility = "public"))

        val notification = Device.waitForShown("timer")
        assertEquals(endsAt, notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(0, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
    }

    // LU-ADAPTER-1
    @Test
    fun visibilityFollowsTheOrigin() {
        for ((name, expected) in listOf(
            "public" to Notification.VISIBILITY_PUBLIC,
            "private" to Notification.VISIBILITY_PRIVATE,
            "secret" to Notification.VISIBILITY_SECRET,
        )) {
            adapter.post(record(name, visibility = name))
            assertEquals(name, expected, Device.waitForShown(name).visibility)
        }
    }

    // LU-ADAPTER-4
    @Test
    fun anAppWithoutALaunchActivityGetsNoContentIntent() {
        val withoutLauncher = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "io.github.eedaegon.liveupdate.absent"
        }
        AndroidAdapter(withoutLauncher).post(record("plain"))

        assertNull(Device.waitForShown("plain").contentIntent)
    }

    // LU-ADAPTER-5, LU-API-2
    @Test
    fun promotionIsRequestedOnlyWhenTheRecordAsksForItOnApi36AndLater() {
        assumeTrue(Build.VERSION.SDK_INT >= 36)
        adapter.post(record("asks", promotionRequested = true))
        adapter.post(record("plain", promotionRequested = false))

        assertTrue(requestsPromotion(Device.waitForShown("asks").extras))
        assertFalse(requestsPromotion(Device.waitForShown("plain").extras))
        val promotions = observedPromotions()
        assertEquals("notApplicable", promotions["plain"])
        val promoted = Device.shown("asks")!!.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0
        assertEquals(if (promoted) "promoted" else "notPromoted", promotions["asks"])
    }

    // LU-API-1, LU-ADAPTER-5
    @Test
    fun belowApi36NothingIsPromoted() {
        val older = AndroidAdapter(context, sdk = 35)
        val capabilities = older.capabilities()
        assertFalse(capabilities.getBoolean("supported"))
        assertFalse(capabilities.getBoolean("promotionEnabled"))
        assertTrue(capabilities.getBoolean("notificationsEnabled"))

        older.post(record("older", promotionRequested = true))

        val notification = Device.waitForShown("older")
        assertFalse(requestsPromotion(notification.extras))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val observed = older.observe().getJSONObject(0)
        assertEquals(true, observed.getBoolean("promotionRequested"))
        assertEquals("notPromoted", observed.getString("promotionState"))
    }

    // LU-ADAPTER-6
    @Test
    fun observationReportsOnlyTheAdaptersOwnNotifications() {
        adapter.post(record("own"))
        Device.waitForShown("own")
        adapter.capabilities()
        foreign("other-tag", Device.NOTIFICATION_ID, null)
        foreign(Device.TAG_PREFIX + "other-id", 2, null)
        Device.waitUntil("three notifications are shown") { manager.activeNotifications.size == 3 }

        val observed = adapter.observe()

        assertEquals(1, observed.length())
        assertEquals("own", observed.getJSONObject(0).getString("id"))
    }

    // LU-LIMIT-4, LU-ADAPTER-6
    @Test
    fun observationReadsAtMost160OfTheAdaptersNotifications() {
        // Android does not let one app show that many notifications, so the entries are built here.
        val own = (0 until 161).map { index -> entry(Device.TAG_PREFIX + "cap-$index", record("cap-$index")) }
        val foreign = (0 until 5).map { index -> entry("other-$index", null) }

        assertEquals(160, adapter.observe((own.take(160) + foreign).toTypedArray()).length())
        assertEquals("NATIVE_ERROR", failureCode { adapter.observe((foreign + own).toTypedArray()) })
    }

    // LU-ADAPTER-6
    @Test
    fun aStaleTimeInThePastIsObservedAsStale() {
        val now = System.currentTimeMillis()
        adapter.post(record("stale", snapshot().put("staleAt", now - 1_000)))
        adapter.post(record("fresh", snapshot().put("staleAt", now + 3_600_000)))
        Device.waitForShown("stale")
        Device.waitForShown("fresh")

        val states = observedStates()

        assertEquals("stale", states["stale"])
        assertEquals("active", states["fresh"])
    }

    // LU-ADAPTER-6, LU-LIST-6
    @Test
    fun anUnreadableNotificationOfTheAdapterFailsTheObservation() {
        adapter.capabilities()
        for (extra in listOf(null, "not json", "{}", record("other-id").toString())) {
            foreign(Device.TAG_PREFIX + "broken", Device.NOTIFICATION_ID, extra)
            Device.waitUntil("the broken notification is shown") { Device.shown("broken") != null }
            assertEquals("NATIVE_ERROR", failureCode { adapter.observe() })
            manager.cancel(Device.TAG_PREFIX + "broken", Device.NOTIFICATION_ID)
            Device.waitForGone("broken")
        }
    }

    // LU-VALID-12
    @Test
    fun postsThatBreakTheContractAreRejectedWithoutANotification() {
        val endsAt = System.currentTimeMillis() + 60_000
        val invalid = listOf(
            record(" "),
            record("x".repeat(257)),
            record("no-snapshot").apply { remove("snapshot") },
            record("no-origin").apply { remove("origin") },
            record("visibility", visibility = "unknown"),
            record("blank-title", snapshot().put("title", " \t")),
            record("long-title", snapshot().put("title", "🍡".repeat(121))),
            record("long-status", snapshot().put("status", "s".repeat(241))),
            record("no-template", snapshot(progress = null)),
            record("both", snapshot().put("timer", timer(1, endsAt))),
            record("too-far", snapshot(progress = 1.5)),
            record("negative", snapshot(progress = -0.1)),
            record("same-time", snapshot(progress = null).put("timer", timer(endsAt, endsAt))),
            record("before-1970", snapshot(progress = null).put("timer", timer(-1, endsAt))),
        )
        for (record in invalid) {
            assertEquals(record.optString("id"), "INVALID_INPUT", failureCode { adapter.post(record) })
        }
        assertEquals("INVALID_INPUT", failureCode { adapter.cancel(" ") })
        assertEquals(0, manager.activeNotifications.size)
        // 120 characters of four UTF-8 bytes each still fit the title.
        adapter.post(record("long-emoji", snapshot().put("title", "🍡".repeat(120))))
        Device.waitForShown("long-emoji")
    }

    // LU-BRIDGE-7, LU-LIMIT-3
    @Test
    fun storageWritesAreBoundedInCountAndBytes() {
        val tooMany = JSONArray().apply { repeat(161) { put(JSONObject()) } }
        val tooLarge = JSONArray().put("x".repeat(1_048_576))
        for (records in listOf(tooMany, tooLarge)) {
            assertEquals("INVALID_INPUT", failureCode { adapter.write(records) })
        }
        assertEquals(0, adapter.read().length())
        adapter.write(JSONArray().put(JSONObject().put("id", "kept")))
        assertEquals("kept", AndroidAdapter(context).read().getJSONObject(0).getString("id"))
    }

    // LU-ADAPTER-8
    @Test
    fun cancelRemovesOnlyItsOwnNotification() {
        adapter.post(record("first"))
        adapter.post(record("second"))
        Device.waitForShown("first")
        Device.waitForShown("second")

        adapter.cancel("first")

        Device.waitForGone("first")
        assertNotNull(Device.shown("second"))
        adapter.cancel("never-posted")
    }

    private fun requestsPromotion(extras: Bundle): Boolean =
        Build.VERSION.SDK_INT >= 36 && extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING)

    private fun observedStates(): Map<String, String> = observed("state")

    private fun observedPromotions(): Map<String, String> = observed("promotionState")

    private fun observed(field: String): Map<String, String> {
        val observed = adapter.observe()
        return (0 until observed.length()).associate { index ->
            val entry = observed.getJSONObject(index)
            entry.getString("id") to entry.getString(field)
        }
    }

    private fun foreign(tag: String, id: Int, extra: String?) {
        val builder = Notification.Builder(context, Device.CHANNEL_ID)
            .setSmallIcon(R.drawable.liveupdate_status)
            .setContentTitle("Foreign")
        if (extra != null) {
            builder.setExtras(Bundle().apply { putString("io.github.1eedaegon.liveupdate.record", extra) })
        }
        manager.notify(tag, id, builder.build())
    }

    private fun entry(tag: String, record: JSONObject?): StatusBarNotification {
        val builder = Notification.Builder(context, Device.CHANNEL_ID)
            .setSmallIcon(R.drawable.liveupdate_status)
            .setContentTitle("Entry")
        if (record != null) {
            builder.setExtras(Bundle().apply { putString("io.github.1eedaegon.liveupdate.record", record.toString()) })
        }
        // The public constructor; Android builds these entries itself.
        @Suppress("DEPRECATION")
        return StatusBarNotification(
            context.packageName,
            context.packageName,
            Device.NOTIFICATION_ID,
            tag,
            Process.myUid(),
            Process.myPid(),
            0,
            builder.build(),
            Process.myUserHandle(),
            System.currentTimeMillis(),
        )
    }

    private fun failureCode(action: () -> Unit): String =
        assertThrows(AdapterFailure::class.java) { action() }.code
}
