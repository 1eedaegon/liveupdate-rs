//! Android interaction event contract and lifecycle tests.

mod common;

use common::FakeDriver;
use liveupdate::{
    ActivityEvent, Capabilities, Driver, DriverRecord, EventBatch, EventKind, LiveUpdate,
    Observation,
};
use serde_json::json;

// LU-V2-EVENT-1
#[test]
fn event_wire_shapes_are_strict_stable_and_javascript_safe() {
    let action = ActivityEvent {
        sequence: 7,
        activity_id: "activity-id".into(),
        occurred_at: 1_700_000_000_000,
        kind: EventKind::Action {
            action_id: "cancel".into(),
        },
    };
    let dismissed = ActivityEvent {
        sequence: 8,
        activity_id: "activity-id".into(),
        occurred_at: 1_700_000_000_100,
        kind: EventKind::Dismissed,
    };
    let batch = EventBatch {
        events: vec![action, dismissed],
        dropped: 2,
    };

    assert_eq!(
        serde_json::to_value(&batch).unwrap(),
        json!({
            "events": [
                {
                    "sequence": 7,
                    "activityId": "activity-id",
                    "occurredAt": 1700000000000_u64,
                    "kind": { "kind": "action", "actionId": "cancel" }
                },
                {
                    "sequence": 8,
                    "activityId": "activity-id",
                    "occurredAt": 1700000000100_u64,
                    "kind": { "kind": "dismissed" }
                }
            ],
            "dropped": 2
        }),
    );
    batch.validate().unwrap();

    let mut unknown = serde_json::to_value(batch).unwrap();
    unknown["events"][0]["kind"]["payload"] = json!("secret");
    assert!(serde_json::from_value::<EventBatch>(unknown).is_err());
}

// LU-V2-EVENT-2
#[test]
fn one_client_atomically_drains_events_and_the_next_drain_is_empty() {
    let driver = FakeDriver::default();
    let expected = EventBatch {
        events: vec![ActivityEvent {
            sequence: 1,
            activity_id: "activity-id".into(),
            occurred_at: 1_000,
            kind: EventKind::Action {
                action_id: "confirm".into(),
            },
        }],
        dropped: 0,
    };
    driver.edit(|state| state.events = expected.clone());
    let client = LiveUpdate::new(driver.clone());

    assert_eq!(client.events().unwrap(), expected);
    assert_eq!(client.events().unwrap(), EventBatch::default());
    assert_eq!(driver.take_calls(), ["drain_events", "drain_events"]);
}

struct DefaultEventDriver;

impl Driver for DefaultEventDriver {
    fn capabilities(&self) -> liveupdate::Result<Capabilities> {
        unreachable!()
    }

    fn read(&self) -> liveupdate::Result<Vec<DriverRecord>> {
        unreachable!()
    }

    fn write(&self, _records: &[DriverRecord]) -> liveupdate::Result<()> {
        unreachable!()
    }

    fn observe(&self) -> liveupdate::Result<Vec<Observation>> {
        unreachable!()
    }

    fn post(&self, _record: &DriverRecord) -> liveupdate::Result<()> {
        unreachable!()
    }

    fn cancel(&self, _id: &str) -> liveupdate::Result<()> {
        unreachable!()
    }
}

// LU-V2-EVENT-3
#[test]
fn custom_drivers_keep_source_compatibility_with_an_empty_event_default() {
    assert_eq!(
        DefaultEventDriver.drain_events().unwrap(),
        EventBatch::default()
    );
}
