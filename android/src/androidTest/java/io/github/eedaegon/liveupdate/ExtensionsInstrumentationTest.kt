package io.github.eedaegon.liveupdate

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.eedaegon.liveupdate.Device.context
import io.github.eedaegon.liveupdate.Device.dispatch
import io.github.eedaegon.liveupdate.Device.manager
import io.github.eedaegon.liveupdate.Device.v2Record
import io.github.eedaegon.liveupdate.Device.v2Snapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** The optional parts of the schema-2 contract, as the adapter builds and keys them on the device. */
@RunWith(AndroidJUnit4::class)
class ExtensionsInstrumentationTest {
    private companion object {
        const val ALERT_CHANNEL = "liveupdate-rs.alerts.v1"
        const val BLOCKED_CHANNEL = "liveupdate-rs.test.blocked"
    }

    private val adapter = AndroidAdapter(context)
    private val standard get() = JSONObject().put("kind", "standard")

    @Before
    fun prepare() {
        Device.prepare()
        LaunchActivity.started.clear()
    }

    @After
    fun clear() {
        LiveUpdateForeground.setListener(null)
        LiveUpdateEvents.setListener(null)
        // Whatever a service of the test still shows under the foreground id goes with the service.
        adapter.capabilities()
        ForegroundProbeService.stop(
            LiveUpdateForeground.NOTIFICATION_ID,
            Notification.Builder(context, Device.CHANNEL_ID).setSmallIcon(R.drawable.liveupdate_status).build(),
        )
        Device.clear()
        NotificationProbe.disallow()
        manager.deleteNotificationChannel(BLOCKED_CHANNEL)
    }

    // LU-V2-LINK-2, LU-ADAPTER-4
    @Test
    fun theContentTapOpensItsUrlInThePackageAndNamesTheActivity() {
        adapter.post(v2Record("link", "standard", snapshot { put("openUrl", "liveupdate-test://orders/42") }))
        val content = Device.waitForShown("link").contentIntent
        assertTrue(content.isImmutable)
        assertTrue(content.isActivity)
        assertEquals(context.packageName, content.creatorPackage)
        send(content)
        val opened = waitForStarted()
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("liveupdate-test://orders/42", opened.dataString)
        assertEquals("link", opened.getStringExtra(EXTRA_ACTIVITY_ID))
        assertNull(opened.getStringExtra(EXTRA_ACTION_ID))

        adapter.post(v2Record("plain-1", "standard", snapshot()))
        adapter.post(v2Record("plain-2", "standard", snapshot()))
        val first = Device.waitForShown("plain-1").contentIntent
        val second = Device.waitForShown("plain-2").contentIntent
        assertNotEquals(first, second)
        LaunchActivity.started.clear()
        send(second)
        val launched = waitForStarted()
        assertEquals(Intent.ACTION_MAIN, launched.action)
        assertNull(launched.data)
        assertEquals("plain-2", launched.getStringExtra(EXTRA_ACTIVITY_ID))
    }

    // LU-V2-LINK-3
    @Test
    fun anActionWithAUrlOpensItAndQueuesNoEvent() {
        val actions = JSONArray()
            .put(action("track", "Track").put("openUrl", "liveupdate-test://orders/42/map"))
            .put(action("cancel", "Cancel"))
        adapter.post(v2Record("actions", "standard", v2Snapshot(standard, actions = actions)))
        val posted = Device.waitForShown("actions").actions
        assertTrue(posted[0].actionIntent.isActivity)
        assertTrue(posted[0].actionIntent.isImmutable)
        assertEquals(context.packageName, posted[0].actionIntent.creatorPackage)
        assertTrue(posted[1].actionIntent.isBroadcast)

        send(posted[0].actionIntent)
        val opened = waitForStarted()
        assertEquals("liveupdate-test://orders/42/map", opened.dataString)
        assertEquals("actions", opened.getStringExtra(EXTRA_ACTIVITY_ID))
        assertEquals("track", opened.getStringExtra(EXTRA_ACTION_ID))
        assertEquals(0, EventStore(context).size())

        // The same URL in another activity is another intent, so that each names its own activity; so is the
        // content's of an activity whose id reads like this activity's and its action's.
        adapter.post(v2Record("actions-2", "standard", v2Snapshot(standard, actions = actions)))
        assertNotEquals(posted[0].actionIntent, Device.waitForShown("actions-2").actions[0].actionIntent)
        val alike = snapshot { put("openUrl", "liveupdate-test://orders/42/map") }
        adapter.post(v2Record("actions/track", "standard", alike))
        adapter.post(v2Record("actions:track", "standard", alike))
        assertNotEquals(posted[0].actionIntent, Device.waitForShown("actions/track").contentIntent)
        assertNotEquals(posted[0].actionIntent, Device.waitForShown("actions:track").contentIntent)
    }

    // LU-V2-LINK-4
    @Test
    fun aUrlThatNothingOpensOrTheContractRefusesPostsNothing() {
        val urls = listOf(
            "nobody-opens-this://orders/42",
            "intent://orders#Intent;end",
            "Liveupdate-Test://orders",
            "liveupdate-test://orders 42",
            "liveupdate-test:",
        )
        urls.forEachIndexed { index, url ->
            val content = snapshot { put("openUrl", url) }
            assertEquals(url, "INVALID_INPUT", failureCode { adapter.post(v2Record("url-$index", "standard", content)) })
            val actions = JSONArray().put(action("track", "Track").put("openUrl", url))
            val opening = v2Snapshot(standard, actions = actions)
            assertEquals(url, "INVALID_INPUT", failureCode { adapter.post(v2Record("act-$index", "standard", opening)) })
        }
        assertTrue(manager.activeNotifications.isEmpty())
    }

    // LU-V2-LINK-5
    @Test
    fun anOpenedIntentBecomesOneEventOfAStoredActivity() {
        val actions = JSONArray().put(action("track", "Track").put("openUrl", "liveupdate-test://orders/42/map"))
        val record = v2Record("opened", "standard", v2Snapshot(standard, actions = actions))
        dispatch("write", JSONObject().put("records", JSONArray().put(record)))
        adapter.post(record)
        val notification = Device.waitForShown("opened")
        var stored = 0
        LiveUpdateEvents.setListener { stored += 1 }

        send(notification.contentIntent)
        val content = waitForStarted()
        assertTrue(LiveUpdateEvents.opened(context, content))
        // The intent's marks are gone, so the same intent is not counted again.
        assertFalse(LiveUpdateEvents.opened(context, content))
        LaunchActivity.started.clear()
        send(notification.actions[0].actionIntent)
        assertTrue(LiveUpdateEvents.opened(context, waitForStarted()))
        assertEquals(2, stored)

        assertFalse(LiveUpdateEvents.opened(context, null))
        assertFalse(LiveUpdateEvents.opened(context, Intent(Intent.ACTION_MAIN)))
        assertFalse(LiveUpdateEvents.opened(context, Intent().putExtra(EXTRA_ACTIVITY_ID, "not-stored")))
        val malformed = Intent().putExtra(EXTRA_ACTIVITY_ID, "opened").putExtra(EXTRA_ACTION_ID, "not valid")
        assertFalse(LiveUpdateEvents.opened(context, malformed))

        val events = dispatch("events").getJSONObject("data").getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("opened", events.getJSONObject(0).getString("activityId"))
        assertEquals("""{"kind":"opened"}""", Device.canonical(events.getJSONObject(0).getJSONObject("kind")))
        assertEquals(
            """{"actionId":"track","kind":"opened"}""",
            Device.canonical(events.getJSONObject(1).getJSONObject("kind")),
        )
    }

    // LU-V2-MODEL-3
    @Test
    fun anUpdateWithAnotherStyleIsBuiltAsThatStyle() {
        val started = v2Record("styles", "progress", snapshot())
        adapter.post(started)
        assertNull(Notification.Builder.recoverBuilder(context, Device.waitForShown("styles")).style)

        val big = JSONObject().put("kind", "bigText").put("value", JSONObject().put("text", "Left at the door"))
        Device.paceUpdate()
        adapter.post(JSONObject(started.toString()).put("snapshot", v2Snapshot(big, revision = 1)))
        val updated = Device.waitForShown("styles", revision = 1)
        assertTrue(Notification.Builder.recoverBuilder(context, updated).style is Notification.BigTextStyle)

        val unknown = v2Record("unknown-origin", "carousel", snapshot())
        assertEquals("INVALID_INPUT", failureCode { adapter.post(unknown) })
        assertNull(Device.shown("unknown-origin"))
    }

    // LU-END-9
    @Test
    fun theLastContentStaysUntilItsTimeNoLongerOngoingAndIsNotObserved() {
        NotificationProbe.allow()
        val live = v2Record("ending", "standard", snapshot())
        adapter.post(live)
        val shown = checkNotNull(Device.shown(Device.waitForShown("ending").let { "ending" }))
        assertTrue(shown.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(shown.notification.deleteIntent)
        assertEquals(1, Device.observed().length())

        Device.paceUpdate()
        val now = System.currentTimeMillis()
        adapter.post(ended(live, dismissAt = now + 60_000))
        var final: Notification? = null
        Device.waitUntil("the last content is no longer ongoing") {
            final = Device.shown("ending")?.notification?.takeIf { it.flags and Notification.FLAG_ONGOING_EVENT == 0 }
            final != null
        }
        val last = checkNotNull(final)
        assertTrue(last.timeoutAfter in 1..60_000)
        assertFalse(last.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        // It reports nothing when it goes.
        assertNull(last.deleteIntent)
        assertEquals("Mochi's snack delivery", last.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(NotificationProbe.heard(shown.key).none { it.removed })
        assertEquals(0, Device.observed().length())

        // Android removes it at its time, and a time that has passed removes it at once.
        Device.paceUpdate()
        adapter.post(ended(live, dismissAt = System.currentTimeMillis() + 1_500))
        Device.waitForGone("ending")
        adapter.post(v2Record("passed", "standard", snapshot()))
        Device.waitForShown("passed")
        adapter.post(ended(v2Record("passed", "standard", snapshot()), dismissAt = System.currentTimeMillis() - 1))
        Device.waitForGone("passed")

        val timed = JSONObject(live.toString()).put("dismissAt", now + 60_000)
        assertEquals("INVALID_INPUT", failureCode { adapter.post(timed) })
    }

    // LU-FGS-3
    @Test
    fun aForegroundPlacementIsKeyedWithoutATagAndObservedByItsRecord() {
        // Another notification of the application under the same id is not the adapter's.
        val foreign = Notification.Builder(context, Device.CHANNEL_ID)
            .setSmallIcon(R.drawable.liveupdate_status).setContentTitle("Other").build()
        adapter.capabilities()
        manager.notify(LiveUpdateForeground.NOTIFICATION_ID, foreign)
        Device.waitUntil("the other notification is shown") { held() != null }
        assertEquals(0, Device.observed().length())

        adapter.post(v2Record("tagged", "standard", snapshot()))
        Device.paceUpdate()
        adapter.post(foreground("ride"))
        Device.waitUntil("the foreground activity replaced it") { held()?.let(::idOf) == "ride" }
        assertNull(Device.shown("ride"))
        Device.waitForShown("tagged")
        val observed = Device.observed()
        assertEquals(setOf("tagged", "ride"), (0 until observed.length()).map { observed.getJSONObject(it).getString("id") }.toSet())

        adapter.cancel("ride")
        Device.waitUntil("the foreground activity is cancelled") { held() == null }
        assertNotNull(Device.shown("tagged"))

        val legacy = Device.record("legacy").apply { getJSONObject("origin").put("placement", "foreground") }
        assertEquals("INVALID_INPUT", failureCode { adapter.post(legacy) })
        val floating = v2Record("floating", "standard", snapshot())
            .apply { getJSONObject("origin").put("placement", "floating") }
        assertEquals("INVALID_INPUT", failureCode { adapter.post(floating) })
        assertNull(held())
    }

    // LU-FGS-4
    @Test
    fun aServiceAttachesToTheStoredActivityAndKeepsItThroughUpdates() {
        NotificationProbe.allow()
        val ride = store(foreground("ride"))
        adapter.post(ride)
        val posted = waitForHeld { !it.isForeground() }
        // The stored receipt is read as the record that was posted.
        val (heldId, built) = checkNotNull(adapter.foregroundNotification())
        assertEquals("ride", heldId)
        assertEquals(Device.canonical(ride), Device.canonical(JSONObject(built.extras.getString(RECORD_EXTRA)!!)))

        Device.paceUpdate()
        ForegroundProbeService.attach(context)
        val attached = waitForHeld { it.isForeground() }
        assertEquals(posted.key, attached.key)
        assertEquals("ride", idOf(attached))
        assertTrue(attached.notification.hasPromotableCharacteristics())

        Device.paceUpdate()
        adapter.post(JSONObject(ride.toString()).put("snapshot", snapshot(revision = 1)))
        val updated = waitForHeld { revisionOf(it) == 1L }
        assertTrue(updated.isForeground())
        assertTrue(NotificationProbe.heard(posted.key).none { it.removed })
        assertEquals(1, Device.observed().length())
    }

    // LU-FGS-5
    @Test
    fun endingAHeldActivityReleasesTheServiceAndTellsTheListener() {
        val released = CopyOnWriteArrayList<String>()
        LiveUpdateForeground.setListener { id -> released += id }
        adapter.post(store(foreground("ride")))
        Device.paceUpdate()
        ForegroundProbeService.attach(context)
        waitForHeld { it.isForeground() }

        adapter.cancel("ride")
        Device.waitUntil("the held notification is gone") { held() == null }
        assertEquals(listOf("ride"), released.toList())

        // A listener that throws does not keep the notification.
        LiveUpdateForeground.setListener { throw IllegalStateException("listener") }
        adapter.post(store(foreground("ride-2")))
        Device.paceUpdate()
        ForegroundProbeService.attach(context)
        waitForHeld { it.isForeground() && idOf(it) == "ride-2" }
        adapter.cancel("ride-2")
        Device.waitUntil("the second held notification is gone") { held() == null }
    }

    // LU-FGS-6
    @Test
    fun detachLeavesAnOrdinaryNotificationAndAttachNeedsALiveForegroundActivity() {
        NotificationProbe.allow()
        val ride = store(foreground("ride"))
        adapter.post(ride)
        Device.paceUpdate()
        ForegroundProbeService.attach(context)
        waitForHeld { it.isForeground() }
        val service = checkNotNull(ForegroundProbeService.running)
        val type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        Device.paceUpdate()
        adapter.post(JSONObject(ride.toString()).put("snapshot", snapshot(revision = 1)))
        waitForHeld { it.isForeground() && revisionOf(it) == 1L }

        // The update went through the service, so what Android posts again for the detached service is it.
        LiveUpdateForeground.detach(service)
        val detached = waitForHeld { !it.isForeground() }
        assertEquals(1L, revisionOf(detached))
        assertTrue(NotificationProbe.heard(detached.key).none { it.removed })
        // The library no longer holds the service: nothing is released when the activity ends later. The service
        // may attach again, and the end then takes the notification with it.
        val released = CopyOnWriteArrayList<String>()
        LiveUpdateForeground.setListener { id -> released += id }
        Device.paceUpdate()
        assertTrue(LiveUpdateForeground.attach(service, type))
        waitForHeld { it.isForeground() }
        LiveUpdateForeground.detach(service)
        waitForHeld { !it.isForeground() }
        Device.paceUpdate()
        assertTrue(LiveUpdateForeground.attach(service, type))
        waitForHeld { it.isForeground() }
        adapter.cancel("ride")
        Device.waitUntil("the held notification is gone") { held() == null }
        assertEquals(listOf("ride"), released.toList())

        store(v2Record("tagged", "standard", snapshot()))
        assertFalse(LiveUpdateForeground.attach(service, type))
        store(foreground("ride").put("state", "ended"))
        assertFalse(LiveUpdateForeground.attach(service, type))
        store(foreground("ride").put("state", "dismissed"))
        assertFalse(LiveUpdateForeground.attach(service, type))
        assertNull(held())

        // A clock that Android would ignore is no notification to hold; the same activity with a newer one is.
        val day = 86_400_000L
        fun clocked(startedAt: Long) = foreground("clocked").put("snapshot", snapshot {
            val timer = Device.timer(startedAt, startedAt + 30 * day)
            put("chronometer", JSONObject().put("timer", timer).put("countsDown", false))
        })
        store(clocked(System.currentTimeMillis() - 15 * day))
        assertFalse(LiveUpdateForeground.attach(service, type))
        assertNull(held())
        store(clocked(System.currentTimeMillis() - 13 * day))
        assertTrue(LiveUpdateForeground.attach(service, type))
        waitForHeld { it.isForeground() && idOf(it) == "clocked" }
        adapter.cancel("clocked")
        Device.waitUntil("the clocked notification is gone") { held() == null }

        // Storage that cannot be read is no reason to fail the service.
        context.getSharedPreferences(Device.PREFERENCES, android.content.Context.MODE_PRIVATE)
            .edit().putString("records", "not json").commit()
        assertFalse(LiveUpdateForeground.attach(service, type))

        // A stored live activity that Android no longer shows, as after a killed process, is shown again,
        // quietly even when its revision alerts.
        val again = foreground("ride-again")
        again.getJSONObject("snapshot").getJSONObject("presentation").put("alert", true)
        store(again)
        assertTrue(LiveUpdateForeground.attach(service, type))
        val restored = waitForHeld { it.isForeground() && idOf(it) == "ride-again" }
        assertEquals(Device.CHANNEL_ID, restored.notification.channelId)
        assertTrue(restored.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)

        // An end right after the attach finds the service, whether or not Android lists the notification yet.
        adapter.cancel("ride-again")
        Device.waitUntil("the restored notification is gone") { held() == null }
    }

    // LU-FGS-7
    @Test
    fun theLastContentOfAHeldActivityReplacesTheServicesNotification() {
        NotificationProbe.allow()
        val released = CopyOnWriteArrayList<String>()
        LiveUpdateForeground.setListener { id -> released += id }
        val ride = store(foreground("ride"))
        adapter.post(ride)
        Device.paceUpdate()
        ForegroundProbeService.attach(context)
        val attached = waitForHeld { it.isForeground() }
        val updated = JSONObject(ride.toString()).put("snapshot", snapshot(revision = 1))
        Device.paceUpdate()
        adapter.post(updated)
        waitForHeld { it.isForeground() && revisionOf(it) == 1L }

        adapter.post(ended(updated, dismissAt = System.currentTimeMillis() + 60_000))
        // The service's notification goes with the service, and the last content has the activity's own tag.
        Device.waitUntil("the service's notification is gone") { held() == null }
        val last = Device.waitForShown("ride", revision = 1)
        assertEquals(0, last.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(0, last.flags and Notification.FLAG_FOREGROUND_SERVICE)
        assertTrue(last.timeoutAfter in 1..60_000)
        assertEquals(listOf("ride"), released.toList())
        Device.waitUntil("the listener heard the service's notification go") {
            NotificationProbe.heard(attached.key).lastOrNull()?.removed == true
        }
        assertEquals(0, Device.observed().length())

        // The placement is free for the next activity, beside the last content of the one before.
        adapter.post(store(foreground("ride-2")))
        Device.waitUntil("the next activity has the foreground id") { held()?.let(::idOf) == "ride-2" }
        assertNotNull(Device.shown("ride"))
        adapter.cancel("ride")
        Device.waitForGone("ride")
        assertEquals("ride-2", held()?.let(::idOf))
    }

    // LU-V2-ALERT-2
    @Test
    fun anAlertingRevisionIsPostedOnTheAlertChannelAndTheNextReturnsToTheQuietOne() {
        NotificationProbe.allow()
        val quiet = v2Record("alerts", "standard", snapshot())
        adapter.post(quiet)
        val key = checkNotNull(Device.shown(Device.waitForShown("alerts").let { "alerts" })).key

        Device.paceUpdate()
        adapter.post(JSONObject(quiet.toString()).put("snapshot", snapshot(revision = 1) { put("alert", true) }))
        val alerting = Device.waitForShown("alerts", revision = 1)
        assertEquals(ALERT_CHANNEL, alerting.channelId)
        assertEquals(0, alerting.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(ALERT_CHANNEL).importance)
        assertEquals("Live update alerts", manager.getNotificationChannel(ALERT_CHANNEL).name)

        Device.paceUpdate()
        adapter.post(JSONObject(quiet.toString()).put("snapshot", snapshot(revision = 2)))
        val after = Device.waitForShown("alerts", revision = 2)
        assertEquals(Device.CHANNEL_ID, after.channelId)
        assertTrue(after.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(NotificationProbe.heard(key).none { it.removed })

        // A user who blocked the alerting channel still sees the revision, quietly.
        manager.createNotificationChannel(NotificationChannel(BLOCKED_CHANNEL, "Blocked", NotificationManager.IMPORTANCE_NONE))
        val blocked = AndroidAdapter(context, alertChannelId = BLOCKED_CHANNEL)
        Device.paceUpdate()
        blocked.post(JSONObject(quiet.toString()).put("snapshot", snapshot(revision = 3) { put("alert", true) }))
        assertEquals(Device.CHANNEL_ID, Device.waitForShown("alerts", revision = 3).channelId)

        val notABoolean = snapshot(revision = 4) { put("alert", "yes") }
        assertEquals("INVALID_INPUT", failureCode { adapter.post(JSONObject(quiet.toString()).put("snapshot", notABoolean)) })
    }

    // LU-V2-PUBLIC-2
    @Test
    fun aPrivateActivityCarriesItsPublicVersion() {
        val public = JSONObject().put("title", "Delivery").put("status", "In progress")
        adapter.post(v2Record("public", "standard", snapshot { put("publicVersion", public) }, visibility = "private"))
        val shown = checkNotNull(Device.waitForShown("public").publicVersion)
        assertEquals("Delivery", shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("In progress", shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(R.drawable.liveupdate_status, shown.smallIcon.resId)

        adapter.post(v2Record("without", "standard", snapshot()))
        assertNull(Device.waitForShown("without").publicVersion)

        val invalid = listOf(
            "public" to snapshot { put("publicVersion", public) },
            "secret" to snapshot { put("publicVersion", public) },
            "private" to snapshot { put("publicVersion", JSONObject(public.toString()).put("icon", "x")) },
            "private" to snapshot { put("publicVersion", JSONObject().put("title", "Delivery")) },
        )
        invalid.forEachIndexed { index, (visibility, content) ->
            val record = v2Record("public-$index", "standard", content, visibility = visibility)
            assertEquals("public-$index", "INVALID_INPUT", failureCode { adapter.post(record) })
        }
    }

    // LU-V2-BRAND-2
    @Test
    fun brandingSetsTheSmallIconTheColorAndTheSubText() {
        val icon = context.resources.getIdentifier("liveupdate_test_small", "drawable", context.packageName)
        assertNotEquals(0, icon)
        val public = JSONObject().put("title", "Delivery").put("status", "In progress")
        adapter.post(v2Record("brand", "standard", snapshot {
            put("smallIcon", Device.resourceIcon("liveupdate_test_small"))
            put("accentColor", 0xff21_96f3L)
            put("subText", "Mochi Express")
            put("publicVersion", public)
        }))
        val branded = Device.waitForShown("brand")
        assertEquals(icon, branded.smallIcon.resId)
        assertEquals(0xff21_96f3L.toInt(), branded.color)
        assertEquals("Mochi Express", branded.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals(icon, checkNotNull(branded.publicVersion).smallIcon.resId)

        adapter.post(v2Record("plain", "standard", snapshot()))
        val plain = Device.waitForShown("plain")
        assertEquals(R.drawable.liveupdate_status, plain.smallIcon.resId)
        assertNull(plain.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))

        val invalid = listOf(
            snapshot { put("smallIcon", Device.appFileIcon("liveupdate-test/icon.png")) },
            snapshot { put("smallIcon", Device.resourceIcon("no_such_drawable")) },
            snapshot { put("accentColor", 0x1_0000_0000L) },
            snapshot { put("subText", "x".repeat(65)) },
        )
        invalid.forEachIndexed { index, content ->
            val record = v2Record("brand-$index", "standard", content)
            assertEquals("brand-$index", "INVALID_INPUT", failureCode { adapter.post(record) })
        }
    }

    private fun snapshot(revision: Long = 0, configure: JSONObject.() -> Unit = {}): JSONObject =
        v2Snapshot(standard, revision = revision).also { it.getJSONObject("presentation").configure() }

    private fun action(id: String, title: String) = JSONObject().put("id", id).put("title", title)

    private fun foreground(id: String): JSONObject = v2Record(id, "standard", snapshot())
        .apply { getJSONObject("origin").put("placement", "foreground") }

    /** [record] as the lifecycle stores it once its activity ended with a time to stay until. */
    private fun ended(record: JSONObject, dismissAt: Long): JSONObject = JSONObject(record.toString())
        .put("state", "ended").put("awaitingObservation", false).put("dismissAt", dismissAt)

    /**
     * Stores [record] alone, as the lifecycle does before it posts: with the state it last answered, which only
     * a stored receipt has. Answers [record] as it is posted, without it.
     */
    private fun store(record: JSONObject): JSONObject {
        val receipt = JSONObject(record.toString()).put("reportedState", "unknown")
        dispatch("write", JSONObject().put("records", JSONArray().put(receipt)))
        return record
    }

    /** Sends an intent as a tap does; the test package may start an activity from the background. */
    private fun send(intent: PendingIntent) {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            options.pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        intent.send(context, 0, null, null, null, null, options.toBundle())
    }

    private fun waitForStarted(): Intent {
        Device.waitUntil("the activity is started") { LaunchActivity.started.isNotEmpty() }
        return LaunchActivity.started.last()
    }

    private fun held(): StatusBarNotification? = manager.activeNotifications
        .firstOrNull { it.tag == null && it.id == LiveUpdateForeground.NOTIFICATION_ID }

    private fun waitForHeld(condition: (StatusBarNotification) -> Boolean): StatusBarNotification {
        var found: StatusBarNotification? = null
        Device.waitUntil("the foreground notification is as expected") {
            found = held()?.takeIf(condition)
            found != null
        }
        return checkNotNull(found)
    }

    private fun StatusBarNotification.isForeground(): Boolean =
        notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0

    private fun idOf(notification: StatusBarNotification): String? =
        notification.notification.extras.getString(RECORD_EXTRA)?.let { JSONObject(it).getString("id") }

    private fun revisionOf(notification: StatusBarNotification): Long =
        JSONObject(checkNotNull(notification.notification.extras.getString(RECORD_EXTRA)))
            .getJSONObject("snapshot").getLong("revision")

    private fun failureCode(action: () -> Unit): String =
        org.junit.Assert.assertThrows(AdapterFailure::class.java) { action() }.code
}
