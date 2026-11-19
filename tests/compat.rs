//! What keeps a later adapter working with this version of the crate: its replies are read for what
//! this version knows.

use liveupdate::{ActivityEvent, Bridge, Driver, Error, ErrorCode, EventKind, JsonDriver};
use serde_json::{Value, json};

/// A bridge that answers every command with one reply.
struct Reply(String);

impl Bridge for Reply {
    fn invoke(&self, _command: &str, _payload: &str) -> liveupdate::Result<String> {
        Ok(self.0.clone())
    }
}

fn answering(reply: Value) -> JsonDriver<Reply> {
    JsonDriver::new(Reply(reply.to_string()))
}

fn success(data: Value) -> Value {
    json!({ "status": "ok", "data": data })
}

// LU-COMPAT-3
#[test]
fn an_envelope_and_an_error_of_a_later_adapter_are_read_for_what_this_version_knows() {
    let error = answering(json!({
        "status": "error",
        "error": { "code": "CONFLICT", "message": "Activity exists.", "cause": "later" },
        "trace": 7
    }))
    .capabilities()
    .unwrap_err();
    assert_eq!(error, Error::new(ErrorCode::Conflict, "Activity exists."));

    let capabilities = answering(json!({
        "status": "ok",
        "data": { "supported": false, "notificationsEnabled": true, "promotionEnabled": false },
        "adapter": "later"
    }))
    .capabilities()
    .unwrap();
    assert!(capabilities.can_post());

    assert_eq!(
        serde_json::from_value::<Error>(
            json!({ "code": "NOT_FOUND", "message": "Gone.", "cause": "later" })
        )
        .unwrap(),
        Error::new(ErrorCode::NotFound, "Gone.")
    );
    // The status says what a reply is, whatever else a later adapter puts beside it.
    let both = |status: &str| {
        json!({
            "status": status,
            "data": { "supported": true, "notificationsEnabled": true, "promotionEnabled": true },
            "error": { "code": "SYSTEM_LIMIT", "message": "Too many." }
        })
    };
    assert!(answering(both("ok")).capabilities().unwrap().can_promote());
    assert_eq!(
        answering(both("error")).capabilities().unwrap_err(),
        Error::new(ErrorCode::SystemLimit, "Too many.")
    );
    // What this version knows is still required, and a code it does not know is no error it can name.
    for unreadable in [
        json!({ "status": "error", "error": { "code": "CONFLICT" }, "trace": 7 }),
        json!({ "status": "error", "error": { "code": "LATER", "message": "m" } }),
        json!({ "status": "later", "data": null }),
    ] {
        let error = answering(unreadable).capabilities().unwrap_err();
        assert_eq!(error.code, ErrorCode::NativeError);
    }
}

// LU-COMPAT-3
#[test]
fn a_batch_of_a_later_adapter_is_read_with_the_kinds_this_version_does_not_know() {
    let event = |sequence: u64, kind: Value| json!({ "sequence": sequence, "activityId": "activity-id", "occurredAt": 1_000, "kind": kind });
    let mut first = event(
        1,
        json!({ "kind": "action", "actionId": "confirm", "source": "watch" }),
    );
    first["device"] = json!("watch");
    let batch = json!({
        "events": [
            first,
            event(2, json!({ "kind": "snoozed", "until": 5 })),
            event(3, json!({ "kind": "dismissed", "by": "user" })),
            event(4, json!({ "kind": "opened", "actionId": "track", "url": "app://x" })),
        ],
        "dropped": 2,
        "oldest": 1
    });

    let read = answering(success(batch)).drain_events().unwrap();

    let expected = [
        EventKind::Action {
            action_id: "confirm".into(),
        },
        EventKind::Unknown,
        EventKind::Dismissed,
        EventKind::Opened {
            action_id: Some("track".into()),
        },
    ];
    assert_eq!(read.dropped, 2);
    assert_eq!(read.events.len(), expected.len());
    for (index, (event, kind)) in read.events.iter().zip(expected).enumerate() {
        assert_eq!(
            *event,
            ActivityEvent::new(index as u64 + 1, "activity-id", 1_000, kind)
        );
    }
    // A kind that is not known has a name of its own on the way out, and is read back.
    let unknown = serde_json::to_value(EventKind::Unknown).unwrap();
    assert_eq!(unknown, json!({ "kind": "unknown" }));
    assert_eq!(
        serde_json::from_value::<EventKind>(unknown).unwrap(),
        EventKind::Unknown
    );
    read.validate().unwrap();
}

// LU-COMPAT-3
#[test]
fn what_this_version_knows_of_an_event_is_still_checked() {
    let event = |kind: Value| json!({ "sequence": 1, "activityId": "activity-id", "occurredAt": 1_000, "kind": kind });
    for kind in [
        json!({ "kind": "action" }),
        json!({ "kind": "action", "actionId": 7 }),
        json!({ "later": true }),
        json!("dismissed"),
    ] {
        let reply = success(json!({ "events": [event(kind.clone())], "dropped": 0 }));
        let error = answering(reply).drain_events().unwrap_err();
        assert_eq!(error.code, ErrorCode::NativeError, "{kind}");
    }
    // The bounds of a batch hold for a kind that is not known, too.
    let repeated = json!({
        "events": [event(json!({ "kind": "later" })), event(json!({ "kind": "later" }))],
        "dropped": 0
    });
    let error = answering(success(repeated)).drain_events().unwrap_err();
    assert_eq!(error.code, ErrorCode::InvalidInput);
    let unnamed = json!({ "sequence": 1, "occurredAt": 1_000, "kind": { "kind": "later" } });
    let reply = success(json!({ "events": [unnamed], "dropped": 0 }));
    assert_eq!(
        answering(reply).drain_events().unwrap_err().code,
        ErrorCode::NativeError
    );
}
