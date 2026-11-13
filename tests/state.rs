//! Which states of its activities the application has not been told, and when to ask again.

mod common;

use common::{FakeDriver, assert_code, request, snapshot};
use liveupdate::{
    ActivityRecord, ActivityState, EndPolicy, ErrorCode, LiveUpdate, NextCheck, Observation,
    PromotionState, Snapshot, StartRequest,
};
use serde_json::json;

fn answered(changes: &[ActivityRecord]) -> Vec<(&str, ActivityState)> {
    changes
        .iter()
        .map(|record| (record.activity_key.as_str(), record.state))
        .collect()
}

fn with_stale_time(key: &str, stale_at: u64) -> StartRequest {
    StartRequest {
        snapshot: Snapshot {
            stale_at: Some(stale_at),
            ..snapshot(0)
        },
        ..request(key)
    }
}

/// Android reports the notification as stale, as the adapter does once its stale time has passed.
fn pass_stale_time(driver: &FakeDriver, id: &str) {
    driver.edit(|state| state.observed.get_mut(id).unwrap().state = ActivityState::Stale);
}

/// Android lists the post of `id` that it had not listed yet.
fn list_late(driver: &FakeDriver, id: &str) {
    driver.edit(|state| {
        let record = state
            .records
            .iter()
            .find(|record| record.id == id)
            .unwrap()
            .clone();
        state.observed.insert(
            id.into(),
            Observation {
                id: id.into(),
                snapshot: record.snapshot,
                state: ActivityState::Active,
                promotion_requested: false,
                promotion_state: PromotionState::NotApplicable,
            },
        );
    });
}

// LU-STATE-1
#[test]
fn each_state_of_an_activity_is_answered_once() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    assert!(client.state_changes().unwrap().is_empty());

    let started = client.start(request("delivery:1")).unwrap();
    let changes = client.state_changes().unwrap();
    assert_eq!(answered(&changes), [("delivery:1", ActivityState::Active)]);
    assert_eq!(changes, client.list().unwrap());
    assert!(client.state_changes().unwrap().is_empty());

    pass_stale_time(&driver, &started.id);
    assert_eq!(
        answered(&client.state_changes().unwrap()),
        [("delivery:1", ActivityState::Stale)]
    );
    assert!(client.state_changes().unwrap().is_empty());

    // An update with content that is not stale is active again.
    client.update(&started.id, snapshot(1)).unwrap();
    assert_eq!(
        answered(&client.state_changes().unwrap()),
        [("delivery:1", ActivityState::Active)]
    );

    client.end(&started.id, EndPolicy::Immediate).unwrap();
    assert_eq!(
        answered(&client.state_changes().unwrap()),
        [("delivery:1", ActivityState::Ended)]
    );
    assert!(client.state_changes().unwrap().is_empty());

    // A removal is answered as dismissed, its `active` before was never asked for, and the answers
    // come in the order of the list: live activities, then the ones that are over.
    let removed = client.start(request("delivery:2")).unwrap();
    client.start(request("delivery:3")).unwrap();
    driver.dismiss(&removed.id);
    assert_eq!(
        answered(&client.state_changes().unwrap()),
        [
            ("delivery:3", ActivityState::Active),
            ("delivery:2", ActivityState::Dismissed)
        ]
    );
    assert!(client.state_changes().unwrap().is_empty());
}

// LU-STATE-2
#[test]
fn an_unknown_state_is_not_answered_and_hides_nothing() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    driver.edit(|state| state.delay_observation = true);
    let started = client.start(request("delivery:1")).unwrap();
    assert_eq!(started.state, ActivityState::Unknown);
    assert!(client.state_changes().unwrap().is_empty());
    list_late(&driver, &started.id);
    assert_eq!(
        answered(&client.state_changes().unwrap()),
        [("delivery:1", ActivityState::Active)]
    );

    // Active, an update Android has not listed yet, active again: silence.
    client.update(&started.id, snapshot(1)).unwrap();
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Unknown);
    assert!(client.state_changes().unwrap().is_empty());
    list_late(&driver, &started.id);
    assert!(client.state_changes().unwrap().is_empty());
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Active);

    // Stale, an update Android lists late, active: one answer, when it is active.
    pass_stale_time(&driver, &started.id);
    assert_eq!(
        answered(&client.state_changes().unwrap()),
        [("delivery:1", ActivityState::Stale)]
    );
    client.update(&started.id, snapshot(2)).unwrap();
    assert!(client.state_changes().unwrap().is_empty());
    list_late(&driver, &started.id);
    assert_eq!(
        answered(&client.state_changes().unwrap()),
        [("delivery:1", ActivityState::Active)]
    );
}

// LU-STATE-3
#[test]
fn a_new_client_continues_and_an_earlier_receipt_is_adopted_unanswered() {
    let driver = FakeDriver::default();
    let started = LiveUpdate::new(driver.clone())
        .start(request("delivery:1"))
        .unwrap();
    assert_eq!(
        driver.records()[0].reported_state,
        Some(ActivityState::Unknown)
    );
    assert_eq!(
        LiveUpdate::new(driver.clone())
            .state_changes()
            .unwrap()
            .len(),
        1
    );
    assert!(
        LiveUpdate::new(driver.clone())
            .state_changes()
            .unwrap()
            .is_empty()
    );
    let stored = serde_json::to_value(&driver.records()[0]).unwrap();
    assert_eq!(stored["reportedState"], json!("active"));

    // A receipt as an earlier version stored it has no such member. Its state is adopted without
    // an answer, and its next state is answered.
    let mut earlier = stored;
    earlier.as_object_mut().unwrap().remove("reportedState");
    driver.edit(|state| state.records = vec![serde_json::from_value(earlier).unwrap()]);
    assert_eq!(driver.records()[0].reported_state, None);
    let client = LiveUpdate::new(driver.clone());
    assert!(client.state_changes().unwrap().is_empty());
    assert_eq!(
        driver.records()[0].reported_state,
        Some(ActivityState::Active)
    );
    pass_stale_time(&driver, &started.id);
    assert_eq!(
        answered(&client.state_changes().unwrap()),
        [("delivery:1", ActivityState::Stale)]
    );

    // An earlier receipt that Android had not listed yet is adopted as unknown, and answered once
    // it is active.
    let driver = FakeDriver::default();
    driver.edit(|state| state.delay_observation = true);
    let client = LiveUpdate::new(driver.clone());
    let awaited = client.start(request("delivery:2")).unwrap();
    driver.edit(|state| state.records[0].reported_state = None);
    assert!(client.state_changes().unwrap().is_empty());
    assert_eq!(
        driver.records()[0].reported_state,
        Some(ActivityState::Unknown)
    );
    list_late(&driver, &awaited.id);
    assert_eq!(
        answered(&client.state_changes().unwrap()),
        [("delivery:2", ActivityState::Active)]
    );
}

// LU-STATE-4
#[test]
fn the_state_last_answered_is_stored_and_never_posted() {
    let driver = FakeDriver::default();
    driver.edit(|state| state.capabilities.presentation.delayed_end = true);
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();
    client.state_changes().unwrap();

    // An update, also one the adapter refuses, and an end leave the answer alone.
    client.update(&started.id, snapshot(1)).unwrap();
    assert_eq!(
        driver.records()[0].reported_state,
        Some(ActivityState::Active)
    );
    driver.edit(|state| state.refuse_next_post = true);
    assert_code(
        client.update(&started.id, snapshot(2)),
        ErrorCode::InvalidInput,
    );
    assert_eq!(
        driver.records()[0].reported_state,
        Some(ActivityState::Active)
    );
    client
        .end(&started.id, EndPolicy::After { at: 5_000 })
        .unwrap();
    assert_eq!(
        driver.records()[0].reported_state,
        Some(ActivityState::Active)
    );

    let posted = driver.edit(|state| state.posted.clone());
    assert_eq!(posted.len(), 4);
    for record in &posted {
        assert_eq!(record.reported_state, None);
        assert!(
            serde_json::to_value(record)
                .unwrap()
                .get("reportedState")
                .is_none()
        );
    }
    // Apart from that member, what is posted last is the stored receipt.
    let mut stored = driver.records()[0].clone();
    stored.reported_state = None;
    assert_eq!(posted[3], stored);
}

// LU-STATE-5
#[test]
fn a_store_that_fails_answers_nothing_and_loses_nothing() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    client.start(request("delivery:1")).unwrap();

    driver.edit(|state| state.fail_write_after = Some(0));
    assert_code(client.state_changes(), ErrorCode::NativeError);
    assert_eq!(
        answered(&client.state_changes().unwrap()),
        [("delivery:1", ActivityState::Active)]
    );

    // With nothing due, nothing is stored.
    driver.take_calls();
    assert!(client.state_changes().unwrap().is_empty());
    assert_eq!(driver.take_calls(), ["read", "observe"]);
}

// LU-STATE-6
#[test]
fn the_next_check_is_now_for_a_due_answer_soon_for_an_awaited_post_then_the_earliest_stale_time() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    assert_eq!(client.next_check().unwrap(), NextCheck::Never);

    driver.edit(|state| state.delay_observation = true);
    let awaited = client.start(with_stale_time("delivery:1", 5_000)).unwrap();
    driver.take_calls();
    assert_eq!(client.next_check().unwrap(), NextCheck::Soon);
    assert_eq!(driver.take_calls(), ["read"]);

    // The receipts know of the listing after a reconciliation. A post that Android never lists
    // keeps asking, so a caller gives up after a few times.
    list_late(&driver, &awaited.id);
    assert_eq!(client.next_check().unwrap(), NextCheck::Soon);
    client.state_changes().unwrap();
    assert_eq!(client.next_check().unwrap(), NextCheck::At(5_000));

    // A state that an operation already reconciled is due now, until it is asked for.
    driver.edit(|state| state.delay_observation = false);
    let earlier = client.start(with_stale_time("delivery:2", 3_000)).unwrap();
    assert_eq!(client.next_check().unwrap(), NextCheck::Now);
    client.state_changes().unwrap();
    let timeless = client.start(request("delivery:3")).unwrap();
    client.state_changes().unwrap();
    assert_eq!(client.next_check().unwrap(), NextCheck::At(3_000));

    // An awaited post comes before any stale time.
    driver.edit(|state| state.delay_observation = true);
    client.update(&timeless.id, snapshot(1)).unwrap();
    assert_eq!(client.next_check().unwrap(), NextCheck::Soon);
    list_late(&driver, &timeless.id);
    client.list().unwrap();
    assert_eq!(client.next_check().unwrap(), NextCheck::At(3_000));
    driver.edit(|state| state.delay_observation = false);

    // An activity that a list found stale is due, and once answered has no stale time ahead.
    pass_stale_time(&driver, &earlier.id);
    client.list().unwrap();
    assert_eq!(client.next_check().unwrap(), NextCheck::Now);
    client.state_changes().unwrap();
    assert_eq!(client.next_check().unwrap(), NextCheck::At(5_000));

    // Nor has one that is over, whose end is due first.
    client.end(&awaited.id, EndPolicy::Immediate).unwrap();
    assert_eq!(client.next_check().unwrap(), NextCheck::Now);
    client.state_changes().unwrap();
    assert_eq!(client.next_check().unwrap(), NextCheck::Never);

    // A post that failed needs its operation again, not time.
    driver.edit(|state| state.fail_next_post = true);
    assert_code(
        client.start(with_stale_time("delivery:4", 1_000)),
        ErrorCode::NativeError,
    );
    assert_eq!(client.next_check().unwrap(), NextCheck::Never);
}
