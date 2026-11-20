package io.github.eedaegon.liveupdate

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.eedaegon.liveupdate.Device.context
import io.github.eedaegon.liveupdate.Device.dispatch
import io.github.eedaegon.liveupdate.Device.v2Record
import io.github.eedaegon.liveupdate.Device.v2Snapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private const val PACKAGE = "io.github.eedaegon.liveupdate"

/** An action whose tap Android delivers to a component of the application, and what the component records. */
@RunWith(AndroidJUnit4::class)
class NativeTargetInstrumentationTest {
    private val adapter = AndroidAdapter(context)
    private val listeners = CopyOnWriteArrayList<LiveUpdateNativeListener>()

    @Before
    fun prepare() {
        Device.prepare()
        TargetProbeService.taps.clear()
    }

    @After
    fun clear() {
        listeners.forEach(LiveUpdateEvents::removeNativeListener)
        LiveUpdateEvents.setListener(null)
        Device.clear()
    }

    private fun target(kind: String, component: String, action: String? = null) = JSONObject()
        .put("kind", kind).put("component", component)
        .apply { if (action != null) put("action", action) }

    private fun action(id: String, target: JSONObject? = null, authentication: Boolean = false) = JSONObject()
        .put("id", id).put("title", id.replaceFirstChar(Char::uppercase))
        .apply {
            if (target != null) put("target", target)
            if (authentication) put("authenticationRequired", true)
        }

    private fun record(id: String, vararg actions: JSONObject) =
        v2Record(id, "standard", v2Snapshot(JSONObject().put("kind", "standard"), actions = JSONArray(actions.toList())))

    /**
     * Stores [record] as the lifecycle does before it posts, and posts it, paced: Android drops the post of a package
     * that the previous test's burst of posts and cancels has pulled over its rate.
     */
    private fun show(record: JSONObject): JSONObject {
        val receipt = JSONObject(record.toString()).put("reportedState", "unknown")
        dispatch("write", JSONObject().put("records", JSONArray().put(receipt)))
        Device.paceUpdate()
        adapter.post(record)
        return record
    }

    private fun drained(): JSONArray = dispatch("events").getJSONObject("data").getJSONArray("events")

    private fun listen(): CopyOnWriteArrayList<LiveUpdateEvent> {
        val heard = CopyOnWriteArrayList<LiveUpdateEvent>()
        val listener = LiveUpdateNativeListener { heard += it }
        listeners += listener
        LiveUpdateEvents.addNativeListener(listener)
        return heard
    }

    // LU-TARGET-2
    @Test
    fun theAdapterOffersTargets() {
        assertTrue(adapter.capabilities().getJSONObject("presentation").getBoolean("actionTargets"))
    }

    // LU-TARGET-3, LU-TARGET-4, LU-V2-EVENT-8
    @Test
    fun eachKindOfTargetReachesItsComponentWithTheIds() {
        show(record(
            "ride",
            action("record", target("foregroundService", "$PACKAGE.TargetProbeForegroundService", "dev.test.RECORD")),
            action("pause", target("service", "$PACKAGE.TargetProbeService")),
            action("mark", target("broadcast", "$PACKAGE.TargetProbeReceiver", "dev.test.MARK")),
        ))
        val actions = Device.waitForShown("ride").actions
        assertEquals(listOf("Record", "Pause", "Mark"), actions.map { it.title.toString() })
        for (each in actions) {
            Device.assertImmutable(each.actionIntent)
            assertEquals(context.packageName, each.actionIntent.creatorPackage)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(actions[0].actionIntent.isForegroundService)
            assertTrue(actions[1].actionIntent.isService)
            assertTrue(actions[2].actionIntent.isBroadcast)
        }
        // Each action has a PendingIntent of its own.
        assertEquals(3, actions.map { it.actionIntent }.toSet().size)

        actions.forEach { it.actionIntent.send() }
        val taken = (1..3).map { checkNotNull(TargetProbeService.taps.poll(15, TimeUnit.SECONDS)) }
            .associateBy { it.component.substringAfterLast('.') }
        assertEquals(setOf("TargetProbeForegroundService", "TargetProbeService", "TargetProbeReceiver"), taken.keys)
        assertEquals("dev.test.RECORD", taken.getValue("TargetProbeForegroundService").intentAction)
        assertEquals("dev.test.MARK", taken.getValue("TargetProbeReceiver").intentAction)
        for ((name, actionId) in listOf(
            "TargetProbeForegroundService" to "record",
            "TargetProbeService" to "pause",
            "TargetProbeReceiver" to "mark",
        )) {
            val tap = checkNotNull(taken.getValue(name).tap) { name }
            assertEquals("ride", tap.activityId)
            assertEquals(actionId, tap.actionId)
            assertFalse(tap.withheld)
        }

        // The application took them: Rust drains them as handled, one each.
        val events = drained()
        assertEquals(3, events.length())
        val kinds = (0 until events.length()).map { events.getJSONObject(it).getJSONObject("kind") }
        assertTrue(kinds.all { it.getString("kind") == "handled" })
        assertEquals(setOf("record", "pause", "mark"), kinds.map { it.getString("actionId") }.toSet())
    }

    // LU-TARGET-3
    @Test
    fun aTargetTheApplicationLacksOrBesideALinkIsRefused() {
        val refused = listOf(
            action("a", target("service", "$PACKAGE.Missing")),
            // A service is no receiver, and a receiver no service.
            action("a", target("broadcast", "$PACKAGE.TargetProbeService")),
            action("a", target("service", "$PACKAGE.TargetProbeReceiver")),
            action("a", target("activity", "$PACKAGE.LaunchActivity")),
            action("a", target("service", "TargetProbeService")),
            action("a", target("service", "$PACKAGE.TargetProbeService", "has space")),
            action("a", JSONObject().put("kind", "service")),
            action("a", target("service", "$PACKAGE.TargetProbeService")).put("openUrl", "liveupdate-test://x"),
            // Any application could send an exported component the marks of a tap.
            action("a", target("service", "$PACKAGE.TargetProbeExportedService")),
            // Another process has its own view of the event store.
            action("a", target("service", "$PACKAGE.TargetProbeRemoteService")),
            action("a", target("service", "dev.fremen.app.\u00dcnicode")),
        )
        refused.forEachIndexed { index, action ->
            val failure = assertThrows("$index", AdapterFailure::class.java) {
                adapter.post(record("refused-$index", action))
            }
            assertEquals("$index", "INVALID_INPUT", failure.code)
        }
        assertTrue(Device.manager.activeNotifications.isEmpty())
    }

    // LU-TARGET-4
    @Test
    fun takeAnswersOnlyATapOfATargetAndRecordsItOnce() {
        show(record("ride", action("record", target("service", "$PACKAGE.TargetProbeService"))))
        fun tapOf(activityId: String) = targetIntent(context, activityId, "record", "$PACKAGE.TargetProbeService", null, false)

        assertNull(LiveUpdateActions.take(context, null))
        assertNull(LiveUpdateActions.take(context, Intent("dev.test.OTHER").putExtra("activityId", "ride")))

        val intent = tapOf("ride")
        val tap = checkNotNull(LiveUpdateActions.take(context, intent))
        assertEquals("ride", tap.activityId)
        assertEquals("record", tap.actionId)
        assertFalse(tap.withheld)
        // An intent delivered again is no second tap.
        assertNull(LiveUpdateActions.take(context, intent))
        // A tap of an activity that is not stored is answered, and not recorded.
        assertNotNull(LiveUpdateActions.take(context, tapOf("gone")))
        // Ids that no notification of the library carries are no tap.
        assertNull(LiveUpdateActions.take(context, tapOf("")))
        assertNull(LiveUpdateActions.take(context, targetIntent(context, "ride", "has space", "$PACKAGE.TargetProbeService", null, false)))

        val events = drained()
        assertEquals(1, events.length())
        assertEquals(
            JSONObject().put("kind", "handled").put("actionId", "record").toString(),
            events.getJSONObject(0).getJSONObject("kind").toString(),
        )

        // A store that cannot be written is logged: the application still has its tap.
        context.getSharedPreferences(Device.PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString("events.v2", "not json").commit()
        val unrecorded = checkNotNull(LiveUpdateActions.take(context, tapOf("ride")))
        assertEquals("record", unrecorded.actionId)
    }

    // LU-TARGET-5
    @Test
    fun belowApi31ALockedDeviceWithholdsATapThatRequiresAuthentication() {
        show(record(
            "ride",
            action("pay", target("service", "$PACKAGE.TargetProbeService"), authentication = true),
            action("record", target("service", "$PACKAGE.TargetProbeService")),
        ))
        fun tap(actionId: String, authentication: Boolean) =
            targetIntent(context, "ride", actionId, "$PACKAGE.TargetProbeService", null, authentication)
        val locked = TargetTap(30) { true }

        val withheld = checkNotNull(locked.take(context, tap("pay", authentication = true)))
        assertTrue(withheld.withheld)
        // An intent that does not say it is read in the stored receipt.
        assertTrue(checkNotNull(locked.take(context, tap("pay", authentication = false))).withheld)
        assertEquals(0, drained().length())

        assertFalse(checkNotNull(locked.take(context, tap("record", authentication = false))).withheld)
        assertFalse(checkNotNull(TargetTap(30) { false }.take(context, tap("pay", authentication = true))).withheld)
        assertFalse(checkNotNull(TargetTap(31) { true }.take(context, tap("pay", authentication = true))).withheld)
        assertEquals(3, drained().length())
    }

    // LU-V2-EVENT-9
    @Test
    fun nativeListenersHearEveryStoredEventAndTakeNone() {
        show(record(
            "ride",
            action("track"),
            action("record", target("service", "$PACKAGE.TargetProbeService")),
        ))
        val shown = Device.waitForShown("ride")
        var told = 0
        LiveUpdateEvents.setListener { told += 1 }
        val throwing = LiveUpdateNativeListener { throw IllegalStateException("a listener that fails") }
        listeners += throwing
        LiveUpdateEvents.addNativeListener(throwing)
        val first = listen()
        val second = listen()

        // A tap through the AAR's receiver, a dismissal, an opening and a tap that a target took.
        shown.actions[0].actionIntent.send()
        Device.waitUntil("the tap is stored") { first.size == 1 }
        checkNotNull(shown.deleteIntent).send()
        Device.waitUntil("the dismissal is stored") { first.size == 2 }
        val opening = Intent().putExtra(EXTRA_ACTIVITY_ID, "ride")
        assertTrue(LiveUpdateEvents.opened(context, opening))
        assertNotNull(LiveUpdateActions.take(context, targetIntent(context, "ride", "record", "$PACKAGE.TargetProbeService", null, false)))

        for (heard in listOf(first, second)) {
            assertEquals(listOf("action", "dismissed", "opened", "handled"), heard.map { it.kind })
            assertEquals(listOf("track", null, null, "record"), heard.map { it.actionId })
            assertTrue(heard.all { it.activityId == "ride" })
            assertEquals(heard.map { it.sequence }.sorted(), heard.map { it.sequence })
        }
        assertEquals(4, told)
        // Nothing was taken from the queue.
        assertEquals(4, drained().length())

        // The second listener goes; the first still hears.
        LiveUpdateEvents.removeNativeListener(listeners.removeAt(listeners.size - 1))
        LiveUpdateActions.take(context, targetIntent(context, "ride", "record", "$PACKAGE.TargetProbeService", null, false))
        assertEquals(5, first.size)
        assertEquals(4, second.size)
    }
}
