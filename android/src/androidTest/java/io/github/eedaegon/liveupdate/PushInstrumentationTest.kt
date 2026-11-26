package io.github.eedaegon.liveupdate

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.eedaegon.liveupdate.Device.context
import io.github.eedaegon.liveupdate.Device.manager
import io.github.eedaegon.liveupdate.Device.v2Snapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A data message from the app's server, handed to `LiveUpdatePush.handle` as a messaging service does. The test
 * process never sets up ndk-context: the push builds its own client from the context it is given, and loads the
 * library that the manifest's meta-data names.
 */
@RunWith(AndroidJUnit4::class)
class PushInstrumentationTest {
    @Before
    fun prepare() = Device.prepare()

    @After
    fun clear() = Device.clear()

    private fun standard(revision: Long, status: String) =
        v2Snapshot(JSONObject().put("kind", "standard"), revision).put("status", status)

    private fun member(command: String, body: JSONObject): Map<String, String> =
        mapOf(LiveUpdatePush.DATA_KEY to body.put("v", 1).put("command", command).toString())

    private fun start(key: String) = member(
        "start",
        JSONObject().put(
            "request",
            JSONObject()
                .put("activityKey", key)
                .put("schemaVersion", 2)
                .put("template", "standard")
                .put("fallback", "notification")
                .put("visibility", "public")
                .put("snapshot", standard(0, "Preparing")),
        ),
    )

    private fun update(key: String, revision: Long, status: String) =
        member("update", JSONObject().put("activityKey", key).put("snapshot", standard(revision, status)))

    private fun end(key: String) =
        member("end", JSONObject().put("activityKey", key).put("policy", JSONObject().put("kind", "immediate")))

    /** The one event that the last push stored. */
    private fun pushed(): JSONObject {
        val events = EventStore(context).drain().getJSONArray("events")
        assertEquals(events.toString(), 1, events.length())
        val event = events.getJSONObject(0)
        assertEquals("pushed", event.getJSONObject("kind").getString("kind"))
        return event
    }

    // LU-PUSH-8
    @Test
    fun aMessageThatIsNotTheLibrarysIsLeftAlone() {
        assertTrue(AndroidAdapter(context).capabilities().getJSONObject("presentation").getBoolean("push"))
        assertFalse(LiveUpdatePush.handle(context, mapOf("chat" to "Hello")))
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(0, EventStore(context).size())
        // A member that is not a payload is the library's, and names nothing to store an event of.
        assertTrue(LiveUpdatePush.handle(context, mapOf(LiveUpdatePush.DATA_KEY to "{")))
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(0, EventStore(context).size())
    }

    // LU-PUSH-8
    @Test
    fun aPushBeginsUpdatesAndEndsTheActivityOfItsKey() {
        assertTrue(LiveUpdatePush.handle(context, start("ride:1")))
        val started = pushed()
        val id = started.getString("activityId")
        assertEquals("start", started.getJSONObject("kind").getString("command"))
        assertEquals("ride:1", started.getJSONObject("kind").getString("activityKey"))
        assertFalse(started.getJSONObject("kind").has("code"))
        Device.waitForShown(id)

        Device.paceUpdate()
        assertTrue(LiveUpdatePush.handle(context, update("ride:1", 1, "Two minutes away")))
        val updated = pushed()
        assertEquals(id, updated.getString("activityId"))
        assertEquals(1, updated.getJSONObject("kind").getLong("revision"))
        val shown = Device.waitForShown(id, revision = 1)
        assertEquals("Two minutes away", shown.extras.getString(android.app.Notification.EXTRA_TEXT))

        // What the lifecycle makes of a stale push, and of a key without a live activity.
        assertTrue(LiveUpdatePush.handle(context, update("ride:1", 0, "Late")))
        assertEquals("STALE_REVISION", pushed().getJSONObject("kind").getString("code"))
        assertTrue(LiveUpdatePush.handle(context, update("ride:9", 2, "Nobody")))
        val unknown = pushed()
        assertEquals("", unknown.getString("activityId"))
        assertEquals("NOT_FOUND", unknown.getJSONObject("kind").getString("code"))

        assertTrue(LiveUpdatePush.handle(context, end("ride:1")))
        assertEquals(id, pushed().getString("activityId"))
        Device.waitForGone(id)
    }
}
