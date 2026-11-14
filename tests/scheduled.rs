//! A start that names its time: stored at once, begun by the adapter, also while the application
//! does not run.

mod common;

use common::{FakeDriver, assert_code, request, snapshot};
use liveupdate::{
    ActivityRecord, ActivityState, AndroidPresentation, Bridge, Driver, DriverRecord, EndPolicy,
    ErrorCode, Fallback, JsonDriver, LiveUpdate, MAX_TIMESTAMP, NextCheck, Observation, Placement,
    PresentationCapabilities, PresentationStyle, PromotionState, Snapshot, StartOutcome,
    StartReport, StartRequest, Template, Visibility,
};
use serde_json::{Value, json};
use std::sync::{Arc, Mutex};

fn scheduled(key: &str, at: u64) -> StartRequest {
    StartRequest {
        activity_key: key.into(),
        schema_version: 2,
        template: Template::Standard,
        fallback: Fallback::Notification,
        visibility: Visibility::Private,
        snapshot: Snapshot {
            progress: None,
            presentation: Some(AndroidPresentation::new(PresentationStyle::Standard)),
            ..snapshot(0)
        },
        placement: Placement::Tagged,
        start_at: Some(at),
    }
}

/// A driver whose adapter offers a scheduled start.
fn offering() -> FakeDriver {
    let driver = FakeDriver::default();
    driver.edit(|state| {
        state.capabilities.presentation.scheduled_start = true;
        state.capabilities.presentation.delayed_end = true;
    });
    driver
}

/// The adapter posts the stored receipt of `id` and Android lists it, as at its time.
fn begin(driver: &FakeDriver, id: &str) {
    driver.edit(|state| {
        let record = state.records.iter().find(|record| record.id == id).unwrap();
        let observation = Observation {
            id: id.into(),
            snapshot: record.snapshot.clone(),
            state: ActivityState::Active,
            promotion_requested: false,
            promotion_state: PromotionState::NotApplicable,
        };
        state.observed.insert(id.into(), observation);
    });
}

fn report(driver: &FakeDriver, id: &str, outcome: StartOutcome) {
    let id = id.to_owned();
    driver.edit(|state| state.starts = vec![StartReport { id, outcome }]);
}

fn stored(driver: &FakeDriver, id: &str) -> DriverRecord {
    let records = driver.records();
    records.into_iter().find(|record| record.id == id).unwrap()
}

fn answered(changes: &[ActivityRecord]) -> Vec<(&str, ActivityState)> {
    changes
        .iter()
        .map(|record| (record.activity_key.as_str(), record.state))
        .collect()
}

// LU-SCHED-1
#[test]
fn a_start_time_is_part_of_a_schema_two_tagged_start_and_left_out_when_unset() {
    let request = scheduled("ride:1", 5_000);
    request.validate().unwrap();
    let json = serde_json::to_value(&request).unwrap();
    assert_eq!(json["startAt"], json!(5_000));
    assert_eq!(
        serde_json::from_value::<StartRequest>(json).unwrap(),
        request
    );
    let unset = StartRequest {
        start_at: None,
        ..request.clone()
    };
    let json = serde_json::to_value(&unset).unwrap();
    assert!(json.get("startAt").is_none());
    assert_eq!(serde_json::from_value::<StartRequest>(json).unwrap(), unset);
    assert_eq!(
        serde_json::to_value(ActivityState::Scheduled).unwrap(),
        json!("scheduled")
    );
    // A record names its start time, and one of a start at once has none.
    let client = LiveUpdate::new(offering());
    let timed = serde_json::to_value(client.start(request.clone()).unwrap()).unwrap();
    assert_eq!(timed["startAt"], json!(5_000));
    let at_once =
        serde_json::to_value(client.start(common::request("delivery:1")).unwrap()).unwrap();
    assert!(at_once.get("startAt").is_none());

    StartRequest {
        start_at: Some(MAX_TIMESTAMP),
        ..request.clone()
    }
    .validate()
    .unwrap();
    for refused in [
        StartRequest {
            start_at: Some(MAX_TIMESTAMP + 1),
            ..request.clone()
        },
        StartRequest {
            placement: Placement::Foreground,
            ..request.clone()
        },
        StartRequest {
            start_at: Some(5_000),
            ..common::request("ride:1")
        },
    ] {
        assert_code(refused.validate(), ErrorCode::InvalidInput);
    }
}

// LU-SCHED-2
#[test]
fn a_scheduled_start_needs_an_adapter_that_offers_it() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());

    assert_code(
        client.start(scheduled("ride:1", 5_000)),
        ErrorCode::UnsupportedFeature,
    );
    assert!(driver.records().is_empty());
    assert!(driver.edit(|state| state.scheduled.is_empty()));
    assert_eq!(driver.counts(), (0, 0));

    let offered: PresentationCapabilities =
        serde_json::from_value(json!({ "scheduledStart": true, "exactStart": true })).unwrap();
    assert!(offered.scheduled_start && offered.exact_start);
    let silent: PresentationCapabilities = serde_json::from_value(json!({})).unwrap();
    assert!(!silent.scheduled_start && !silent.exact_start);
}

// LU-SCHED-3
#[test]
fn a_scheduled_start_is_stored_and_scheduled_instead_of_posted() {
    let driver = offering();
    let client = LiveUpdate::new(driver.clone());
    client.list().unwrap();
    driver.take_calls();

    let record = client.start(scheduled("ride:1", 5_000)).unwrap();

    assert_eq!(
        driver.take_calls(),
        [
            "capabilities",
            "read",
            "observe",
            "write",
            "schedule",
            "starts",
            "observe",
            "write",
            "read"
        ]
    );
    assert_eq!(record.state, ActivityState::Scheduled);
    assert_eq!(record.start_at, Some(5_000));
    assert_eq!(driver.counts(), (0, 0));
    let receipt = stored(&driver, &record.id);
    assert!(!receipt.awaiting_observation && !receipt.post_failed);
    assert_eq!(receipt.origin.start_at, Some(5_000));
    // What is scheduled is the receipt as a post has it, without the state last answered.
    let given = driver.edit(|state| state.scheduled.clone());
    assert_eq!(given.len(), 1);
    assert_eq!(given[0].id, record.id);
    assert_eq!(given[0].state, ActivityState::Scheduled);
    assert_eq!(given[0].reported_state, None);
    assert_eq!(client.list().unwrap(), std::slice::from_ref(&record));

    // It holds its key: the same start answers it, and another one is a conflict.
    driver.take_calls();
    assert_eq!(client.start(scheduled("ride:1", 5_000)).unwrap(), record);
    assert!(!driver.take_calls().contains(&"schedule"));
    assert_code(
        client.start(scheduled("ride:1", 6_000)),
        ErrorCode::Conflict,
    );
    assert_code(client.start(request("ride:1")), ErrorCode::Conflict);

    // It counts for the limit of live activities.
    for index in 2..=32 {
        client
            .start(scheduled(&format!("ride:{index}"), 5_000))
            .unwrap();
    }
    assert_code(
        client.start(scheduled("ride:33", 5_000)),
        ErrorCode::SystemLimit,
    );
    assert_code(client.start(request("delivery:1")), ErrorCode::SystemLimit);
}

// LU-SCHED-4
#[test]
fn a_refused_scheduling_leaves_no_receipt_and_a_failed_one_is_scheduled_again() {
    let driver = offering();
    let client = LiveUpdate::new(driver.clone());

    driver.edit(|state| state.refuse_next_schedule = true);
    assert_code(
        client.start(scheduled("ride:1", 5_000)),
        ErrorCode::InvalidInput,
    );
    assert!(driver.records().is_empty());

    driver.edit(|state| state.fail_next_schedule = true);
    assert_code(
        client.start(scheduled("ride:1", 5_000)),
        ErrorCode::NativeError,
    );
    let pending = driver.records();
    assert_eq!(pending.len(), 1);
    assert_eq!(pending[0].state, ActivityState::Scheduled);
    assert!(pending[0].post_failed);
    // A scheduling that failed needs its start again, not time, and is not answered as scheduled.
    assert_eq!(client.next_check().unwrap(), NextCheck::Never);
    assert!(client.state_changes().unwrap().is_empty());

    let record = client.start(scheduled("ride:1", 5_000)).unwrap();
    assert_eq!(record.id, pending[0].id);
    assert_eq!(record.state, ActivityState::Scheduled);
    assert!(!stored(&driver, &record.id).post_failed);
    assert_eq!(
        answered(&client.state_changes().unwrap()),
        [("ride:1", ActivityState::Scheduled)]
    );
    assert_eq!(driver.edit(|state| state.scheduled.len()), 3);
    assert_eq!(driver.counts(), (0, 0));

    // An adapter that reports on a scheduling that failed has taken it after all.
    driver.edit(|state| state.fail_next_schedule = true);
    assert_code(
        client.start(scheduled("ride:2", 6_000)),
        ErrorCode::NativeError,
    );
    let taken = driver.records()[1].id.clone();
    report(&driver, &taken, StartOutcome::Posted);
    assert_eq!(client.list().unwrap()[1].state, ActivityState::Unknown);
    let receipt = stored(&driver, &taken);
    assert!(receipt.awaiting_observation && !receipt.post_failed);
    assert_eq!(client.next_check().unwrap(), NextCheck::Soon);

    // A scheduling that failed can be ended like any other.
    driver.edit(|state| {
        state.starts.clear();
        state.fail_next_schedule = true;
    });
    assert_code(
        client.start(scheduled("ride:3", 7_000)),
        ErrorCode::NativeError,
    );
    let failed = driver.records()[2].id.clone();
    let ended = client.end(&failed, EndPolicy::Immediate).unwrap();
    assert_eq!(ended.state, ActivityState::Ended);
    client.start(scheduled("ride:3", 7_000)).unwrap();
}

// LU-SCHED-5
#[test]
fn a_scheduled_activity_waits_begins_or_is_dismissed_by_what_the_adapter_reports() {
    let driver = offering();
    let client = LiveUpdate::new(driver.clone());
    let waiting = client.start(scheduled("ride:1", 5_000)).unwrap();
    driver.take_calls();

    // Nothing reported and nothing listed: it waits. The adapter is asked before the observation.
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Scheduled);
    assert_eq!(driver.take_calls(), ["read", "starts", "observe"]);

    // Posted a moment ago: unknown until Android lists it, as after any post.
    report(&driver, &waiting.id, StartOutcome::Posted);
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Unknown);
    assert!(stored(&driver, &waiting.id).awaiting_observation);
    assert_eq!(client.next_check().unwrap(), NextCheck::Soon);
    driver.edit(|state| state.starts.clear());
    begin(&driver, &waiting.id);
    driver.take_calls();
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Active);
    // No receipt is scheduled any more, so the adapter is not asked.
    assert_eq!(driver.take_calls(), ["read", "observe", "write"]);
    assert_eq!(client.list().unwrap()[0].start_at, Some(5_000));

    // The same start still answers the activity that began.
    assert_eq!(
        client.start(scheduled("ride:1", 5_000)).unwrap().id,
        waiting.id
    );

    // Listed without a report: it began while the application did not run. What Android lists
    // counts, whatever the adapter reports beside it.
    let begun = client.start(scheduled("ride:2", 6_000)).unwrap();
    begin(&driver, &begun.id);
    report(&driver, &begun.id, StartOutcome::Gone);
    assert_eq!(client.list().unwrap()[1].state, ActivityState::Active);
    assert!(!stored(&driver, &begun.id).awaiting_observation);

    // Gone: posted and no longer listed, or never posted. Nothing is posted for it again.
    let gone = client.start(scheduled("ride:3", 7_000)).unwrap();
    // A report for an activity that is not scheduled changes nothing.
    driver.edit(|state| {
        state.starts = [&gone.id, &begun.id]
            .map(|id| StartReport {
                id: id.clone(),
                outcome: StartOutcome::Gone,
            })
            .into();
    });
    let listed = client.list().unwrap();
    assert_eq!(listed[1].state, ActivityState::Active);
    assert_eq!(listed[2].state, ActivityState::Dismissed);
    assert_eq!(driver.counts(), (0, 0));
    // It is answered as dismissed, stays so while the report is repeated, and its key is free.
    let changes = client.state_changes().unwrap();
    assert!(
        changes
            .iter()
            .any(|record| record.id == gone.id && record.state == ActivityState::Dismissed)
    );
    assert_eq!(client.list().unwrap()[2].state, ActivityState::Dismissed);
    let after = client.start(scheduled("ride:3", 7_000)).unwrap();
    assert_ne!(after.id, gone.id);
    client.end(&after.id, EndPolicy::Immediate).unwrap();

    // An adapter that cannot be asked fails the operation, and nothing changes.
    let asked = client.start(scheduled("ride:5", 9_000)).unwrap();
    let before = driver.records();
    driver.edit(|state| state.fail_next_starts = true);
    assert_code(client.list(), ErrorCode::NativeError);
    assert_eq!(driver.records(), before);
    client.end(&asked.id, EndPolicy::Immediate).unwrap();

    // A report that names no activity, or one twice, is not read.
    let again = client.start(scheduled("ride:4", 8_000)).unwrap();
    for ids in [vec![" "], vec![&again.id, &again.id]] {
        driver.edit(|state| {
            state.starts = ids
                .iter()
                .map(|id| StartReport {
                    id: (*id).into(),
                    outcome: StartOutcome::Posted,
                })
                .collect();
        });
        assert_code(client.list(), ErrorCode::NativeError);
    }

    // Nor is a stored receipt that is scheduled without a time.
    driver.edit(|state| {
        state.starts.clear();
        let receipt = state
            .records
            .iter_mut()
            .find(|record| record.id == again.id);
        receipt.unwrap().origin.start_at = None;
    });
    assert_code(client.list(), ErrorCode::NativeError);
}

// LU-SCHED-6
#[test]
fn a_scheduled_activity_refuses_an_update_and_its_end_leaves_nothing() {
    let driver = offering();
    let client = LiveUpdate::new(driver.clone());
    let record = client.start(scheduled("ride:1", 5_000)).unwrap();
    let newer = Snapshot {
        revision: 1,
        ..record.snapshot.clone()
    };

    assert_code(client.update(&record.id, newer), ErrorCode::Conflict);
    assert_eq!(stored(&driver, &record.id).snapshot.revision, 0);

    let ended = client.end(&record.id, EndPolicy::Immediate).unwrap();
    assert_eq!(ended.state, ActivityState::Ended);
    assert_eq!(driver.counts(), (0, 1));

    // A delayed end keeps no last content of what was never shown.
    let second = client.start(scheduled("ride:1", 5_000)).unwrap();
    assert_ne!(second.id, record.id);
    // It still needs an adapter that offers one, as for any activity.
    driver.edit(|state| state.capabilities.presentation.delayed_end = false);
    assert_code(
        client.end(&second.id, EndPolicy::After { at: 9_000 }),
        ErrorCode::UnsupportedFeature,
    );
    assert_eq!(stored(&driver, &second.id).state, ActivityState::Scheduled);
    driver.edit(|state| state.capabilities.presentation.delayed_end = true);
    let ended = client
        .end(&second.id, EndPolicy::After { at: 9_000 })
        .unwrap();
    assert_eq!(ended.state, ActivityState::Ended);
    assert_eq!(stored(&driver, &second.id).dismiss_at, None);
    assert_eq!(driver.counts(), (0, 2));
    assert!(driver.edit(|state| state.finals.is_empty()));
}

// LU-SCHED-7
#[test]
fn scheduled_is_answered_once_and_the_next_check_names_the_start_time() {
    let driver = offering();
    let client = LiveUpdate::new(driver.clone());
    let ride = client.start(scheduled("ride:1", 9_000)).unwrap();

    assert_eq!(
        answered(&client.state_changes().unwrap()),
        [("ride:1", ActivityState::Scheduled)]
    );
    assert!(client.state_changes().unwrap().is_empty());
    driver.take_calls();
    assert_eq!(client.next_check().unwrap(), NextCheck::At(9_000));
    assert_eq!(driver.take_calls(), ["read"]);

    // The earliest of the start times and the stale times.
    client.start(scheduled("ride:2", 4_000)).unwrap();
    client.state_changes().unwrap();
    assert_eq!(client.next_check().unwrap(), NextCheck::At(4_000));
    let stale = StartRequest {
        snapshot: Snapshot {
            stale_at: Some(2_000),
            ..snapshot(0)
        },
        ..request("delivery:1")
    };
    client.start(stale).unwrap();
    client.state_changes().unwrap();
    assert_eq!(client.next_check().unwrap(), NextCheck::At(2_000));

    begin(&driver, &ride.id);
    assert_eq!(
        answered(&client.state_changes().unwrap()),
        [("ride:1", ActivityState::Active)]
    );
    assert!(client.state_changes().unwrap().is_empty());
}

#[derive(Clone, Default)]
struct Recording {
    replies: Arc<Mutex<Vec<Value>>>,
    calls: Arc<Mutex<Vec<(String, Value)>>>,
}

impl Bridge for Recording {
    fn invoke(&self, command: &str, payload: &str) -> liveupdate::Result<String> {
        let payload = serde_json::from_str(payload).unwrap();
        self.calls.lock().unwrap().push((command.into(), payload));
        Ok(self.replies.lock().unwrap().remove(0).to_string())
    }
}

struct Plain;

impl Driver for Plain {
    fn capabilities(&self) -> liveupdate::Result<liveupdate::Capabilities> {
        unreachable!()
    }
    fn read(&self) -> liveupdate::Result<Vec<DriverRecord>> {
        unreachable!()
    }
    fn write(&self, _: &[DriverRecord]) -> liveupdate::Result<()> {
        unreachable!()
    }
    fn observe(&self) -> liveupdate::Result<Vec<Observation>> {
        unreachable!()
    }
    fn post(&self, _: &DriverRecord) -> liveupdate::Result<()> {
        unreachable!()
    }
    fn cancel(&self, _: &str) -> liveupdate::Result<()> {
        unreachable!()
    }
}

// LU-SCHED-8
#[test]
fn scheduling_and_its_report_are_two_bridge_commands_and_a_driver_without_them_offers_none() {
    let driver = offering();
    let record = LiveUpdate::new(driver.clone())
        .start(scheduled("ride:1", 5_000))
        .unwrap();
    let receipt = stored(&driver, &record.id);
    let bridge = Recording::default();
    *bridge.replies.lock().unwrap() = vec![
        json!({ "status": "ok", "data": null }),
        json!({ "status": "ok", "data": [
            { "id": "a", "outcome": "posted" },
            { "id": "b", "outcome": "gone" }
        ] }),
        json!({ "status": "ok", "data": [{ "id": "a", "outcome": "later" }] }),
    ];
    let json = JsonDriver::new(bridge.clone());

    json.schedule(&receipt).unwrap();
    let reports = json.starts().unwrap();
    assert_code(json.starts(), ErrorCode::NativeError);

    let outcomes: Vec<_> = reports
        .iter()
        .map(|report| (report.id.as_str(), report.outcome))
        .collect();
    assert_eq!(
        outcomes,
        [("a", StartOutcome::Posted), ("b", StartOutcome::Gone)]
    );
    let calls = bridge.calls.lock().unwrap().clone();
    assert_eq!(calls[0].0, "schedule");
    assert_eq!(calls[0].1, json!({ "record": receipt }));
    assert_eq!(calls[0].1["record"]["state"], json!("scheduled"));
    assert_eq!(calls[0].1["record"]["origin"]["startAt"], json!(5_000));
    assert_eq!(calls[1], ("starts".into(), json!({})));

    // A driver written before a scheduled start still compiles, and offers none.
    assert_code(Plain.schedule(&receipt), ErrorCode::UnsupportedFeature);
    assert!(Plain.starts().unwrap().is_empty());
}
