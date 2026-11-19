package io.github.eedaegon.liveupdate

import android.app.Notification
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.eedaegon.liveupdate.Device.context
import io.github.eedaegon.liveupdate.Device.resourceIcon
import io.github.eedaegon.liveupdate.Device.v2Record
import io.github.eedaegon.liveupdate.Device.v2Snapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What a later crate sends, and what a later AAR stored, to the adapter of this version. */
@RunWith(AndroidJUnit4::class)
class CompatibilityInstrumentationTest {
    private val adapter = AndroidAdapter(context)

    @Before
    fun prepare() = Device.prepare()

    @After
    fun clear() = Device.clear()

    // LU-COMPAT-1
    @Test
    fun aRecordOfALaterCrateIsPostedWithoutWhatThisVersionDoesNotKnow() {
        val now = System.currentTimeMillis()
        val action = JSONObject().put("id", "track").put("title", "Track")
            .put("icon", resourceIcon("liveupdate_status").later()).later()
        val style = JSONObject().put("kind", "bigText")
            .put("value", JSONObject().put("text", "Every detail").later()).later()
        val chronometer = JSONObject()
            .put("timer", Device.timer(now - 1_000, now + 60_000).later()).put("countsDown", true).later()
        val snapshot = v2Snapshot(
            style,
            actions = JSONArray().put(action),
            samsungNowBar = JSONObject().put("primary", "Order").later(),
            chronometer = chronometer,
        ).later()
        snapshot.getJSONObject("presentation").later()
            .put("publicVersion", JSONObject().put("title", "Delivery").put("status", "On its way").later())
        val record = v2Record("later", "bigText", snapshot).later()
        record.getJSONObject("origin").later()

        adapter.post(record)

        val shown = Device.waitForShown("later")
        assertEquals(Device.BIG_TEXT_STYLE, Device.styleOf(shown))
        assertEquals("Every detail", shown.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(listOf("Track"), shown.actions.map { it.title.toString() })
        assertEquals(now + 60_000, shown.`when`)
        assertTrue(shown.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        val public = checkNotNull(shown.publicVersion)
        assertEquals("Delivery", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // The record inside the notification is the record as it came: the lifecycle compares it with its own.
        val inside = JSONObject(shown.extras.getString(RECORD_EXTRA)!!)
        assertEquals(Device.canonical(record), Device.canonical(inside))
        val observed = Device.observed().getJSONObject(0)
        assertEquals(Device.canonical(snapshot), Device.canonical(observed.getJSONObject("snapshot")))
    }

    // LU-COMPAT-1
    @Test
    fun everyObjectOfARecordMayHaveMoreMembers() {
        val now = System.currentTimeMillis()
        val progress = JSONObject().put("kind", "progress").put("value", JSONObject()
            .put("progress", 25)
            .put("segments", JSONArray().put(JSONObject().put("length", 100).put("id", 1).later()))
            .put("points", JSONArray().put(JSONObject().put("position", 50).put("id", 2).later()))
            .put("trackerIcon", resourceIcon("liveupdate_status").later())
            .later()).later()
        adapter.post(v2Record("progress", "progress", v2Snapshot(progress)))
        assertEquals(25, Device.waitForShown("progress").extras.getInt(Notification.EXTRA_PROGRESS))

        fun metric(label: String, kind: String, value: Any) = JSONObject().put("label", label)
            .put("value", JSONObject().put("kind", kind).put("value", value).later()).later()
        fun metrics(vararg values: JSONObject) = JSONObject().put("kind", "metric").put("value", JSONObject()
            .put("metrics", JSONArray().apply { values.forEach { put(it) } }).later()).later()
        val values = metrics(
            metric("Steps", "integer", JSONObject().put("value", 42).later()),
            metric("Speed", "decimal", JSONObject().put("value", 12.5).later()),
            metric("State", "text", "Ready"),
        )
        val clocks = metrics(
            metric("Elapsed", "stopwatch", JSONObject().put("startedAt", now).later()),
            metric("Left", "countdown", JSONObject().put("endsAt", now + 60_000).later()),
        )
        // Below API 37 the metrics are text, and on it the system's style: both read the same members.
        for (sdk in listOf(36, 37)) {
            val older = AndroidAdapter(context, sdk = sdk)
            older.post(v2Record("values-$sdk", "metric", v2Snapshot(values)))
            older.post(v2Record("clocks-$sdk", "metric", v2Snapshot(clocks)))
            Device.waitForShown("values-$sdk")
            Device.waitForShown("clocks-$sdk")
        }

        val timer = JSONObject().put("kind", "timer").put("value", JSONObject()
            .put("timer", Device.timer(now - 1_000, now + 60_000).later()).put("countsDown", true).later()).later()
        adapter.post(v2Record("timer", "timer", v2Snapshot(timer)))
        assertEquals(now + 60_000, Device.waitForShown("timer").`when`)

        // Schema version 1 has fewer objects, and each of them takes more.
        val legacy = Device.snapshot(progress = null).put("timer", Device.timer(now, now + 60_000).later()).later()
        val record = Device.record("legacy", legacy).later()
        record.getJSONObject("origin").later()
        adapter.post(record)
        assertEquals(now + 60_000, Device.waitForShown("legacy").`when`)
    }

    // LU-COMPAT-1
    @Test
    fun whatThisVersionKnowsOfARecordIsCheckedAsBefore() {
        val standard = JSONObject().put("kind", "standard")
        fun presented(id: String, change: JSONObject.() -> Unit): JSONObject {
            val snapshot = v2Snapshot(JSONObject(standard.toString()))
            snapshot.getJSONObject("presentation").change()
            return v2Record(id, "standard", snapshot)
        }
        val refused = listOf(
            // A value that this version does not know says nothing of what to show instead.
            v2Record("style", "standard", v2Snapshot(JSONObject().put("kind", "carousel").later())),
            v2Record("visibility", "standard", v2Snapshot(standard), visibility = "later"),
            v2Record("state", "standard", v2Snapshot(standard)).put("state", "paused"),
            v2Record("template", "carousel", v2Snapshot(standard)),
            presented("icon-kind") { put("largeIcon", JSONObject().put("kind", "url").put("value", "x").later()) },
            // What must be there must be there, whatever else is.
            v2Record("no-title", "standard", v2Snapshot(standard).later().apply { remove("title") }),
            presented("no-style") { remove("style"); later() },
            presented("no-action-title") { put("actions", JSONArray().put(JSONObject().put("id", "track").later())) },
            presented("no-icon-value") { put("largeIcon", JSONObject().put("kind", "resource").later()) },
            v2Record("no-origin", "standard", v2Snapshot(standard)).later().apply { remove("origin") },
            v2Record("no-promotion", "standard", v2Snapshot(standard)).later().apply { remove("promotionRequested") },
            // A member that is known is of its type, and in its place: a presentation is of schema version 2.
            Device.record("legacy-presentation").apply {
                getJSONObject("snapshot").put("presentation", JSONObject().put("style", JSONObject(standard.toString())))
            },
            v2Record("promotion", "standard", v2Snapshot(standard)).put("promotionRequested", "yes"),
            // The state last answered is the stored receipt's: a record that is posted has none.
            v2Record("stored", "standard", v2Snapshot(standard)).put("reportedState", "unknown"),
            presented("alert") { put("alert", "yes") },
            presented("sub-text") { put("subText", 7) },
            presented("actions") { put("actions", JSONObject().later()) },
        )
        for (record in refused) {
            val failure = assertThrows(record.optString("id"), AdapterFailure::class.java) { adapter.post(record) }
            assertEquals(record.optString("id"), "INVALID_INPUT", failure.code)
        }
        assertTrue(Device.manager.activeNotifications.isEmpty())
        // The record they are all made of is posted as it is.
        adapter.post(presented("valid") {})
        Device.waitForShown("valid")
    }

    // LU-COMPAT-6
    @Test
    fun theEventQueueKeepsAndHandsOnWhatALaterVersionStored() {
        val preferences = context.getSharedPreferences(Device.PREFERENCES, Context.MODE_PRIVATE)
        fun event(sequence: Int, kind: JSONObject) = JSONObject()
            .put("sequence", sequence).put("activityId", "activity").put("occurredAt", 1_000).put("kind", kind)
        val stored = JSONArray()
            .put(event(1, JSONObject().put("kind", "action").put("actionId", "track").later()).later())
            .put(event(2, JSONObject().put("kind", "snoozed").put("until", 5)))
            .put(event(3, JSONObject().put("kind", "dismissed").later()))
            .put(event(4, JSONObject().put("kind", "opened").put("url", "app://x")))
        preferences.edit().putString("events.v2", stored.toString()).putLong("eventSequence.v2", 4).commit()
        val store = EventStore(context)

        // A later event does not stop this version's: the next one is appended behind it.
        store.appendDismissed("activity")
        val batch = store.drain()

        val events = batch.getJSONArray("events")
        assertEquals(5, events.length())
        for (index in 0 until stored.length()) {
            assertEquals(Device.canonical(stored.getJSONObject(index)), Device.canonical(events.getJSONObject(index)))
        }
        assertEquals(5, events.getJSONObject(4).getInt("sequence"))
        assertEquals("dismissed", events.getJSONObject(4).getJSONObject("kind").getString("kind"))
        assertEquals(0, store.drain().getJSONArray("events").length())

        // What this version knows of a stored event is checked as before.
        val unreadable = listOf(
            event(1, JSONObject().put("kind", "action").later()),
            event(1, JSONObject().put("kind", "action").put("actionId", "not valid")),
            event(1, JSONObject().put("kind", "opened").put("actionId", 7)),
            event(1, JSONObject().put("kind", 7)),
            event(1, JSONObject().later()),
            event(1, JSONObject().put("kind", "later")).apply { remove("activityId") },
            event(1, JSONObject().put("kind", "later")).put("sequence", "one"),
        )
        for (broken in unreadable) {
            preferences.edit().putString("events.v2", JSONArray().put(broken).toString()).commit()
            val failure = assertThrows(broken.toString(), AdapterFailure::class.java) { store.drain() }
            assertEquals(broken.toString(), "NATIVE_ERROR", failure.code)
        }
    }

    // LU-COMPAT-6
    @Test
    fun theEventQueueMakesRoomForTheNextEventBesideLargeOnesOfALaterVersion() {
        val preferences = context.getSharedPreferences(Device.PREFERENCES, Context.MODE_PRIVATE)
        fun event(sequence: Int, filler: Int) = JSONObject()
            .put("sequence", sequence).put("activityId", "activity").put("occurredAt", 1_000)
            .put("kind", JSONObject().put("kind", "later").put("payload", "x".repeat(filler)))
        // Three events of a later version, ten bytes below what the queue may take.
        val bound = 1_048_576
        val third = bound / 3
        val rest = JSONArray().put(event(1, third)).put(event(2, third)).put(event(3, 0)).toString().length
        val stored = JSONArray().put(event(1, third)).put(event(2, third)).put(event(3, bound - 10 - rest))
        assertEquals(bound - 10, stored.toString().length)
        preferences.edit().putString("events.v2", stored.toString()).putLong("eventSequence.v2", 3).commit()
        val store = EventStore(context)
        assertEquals(3, store.size())

        // The next event does not fit beside all three: the oldest goes, and is counted.
        store.appendDismissed("activity")

        val batch = store.drain()
        val sequences = (0 until batch.getJSONArray("events").length())
            .map { batch.getJSONArray("events").getJSONObject(it).getInt("sequence") }
        assertEquals(listOf(2, 3, 4), sequences)
        assertEquals(1, batch.getInt("dropped"))
        // And one event that is alone over the bound is stored without any other.
        preferences.edit().putString("events.v2", JSONArray().put(event(5, bound - 200)).toString())
            .putLong("eventSequence.v2", 5).commit()
        store.appendAction("activity", "a".repeat(64))
        store.appendAction("activity", "b".repeat(64))
        assertEquals(2, store.size())
        assertEquals(1, store.drain().getInt("dropped"))
    }

    /** One more member, of a kind no version has: what a later crate or a later AAR may add to any object. */
    private fun JSONObject.later(): JSONObject = put("laterMember", JSONObject().put("of", JSONArray().put(1)))
}
