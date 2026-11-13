//! Start, update and end through the Rust lifecycle, against a deterministic Android driver.

mod common;

use common::{FakeDriver, assert_code, request, snapshot, timer_snapshot};
use liveupdate::{
    ActivityState, EndPolicy, ErrorCode, Fallback, LiveUpdate, MAX_TIMESTAMP, PromotionState,
    Snapshot, Template, Timer, Visibility,
};
use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    sync::Arc,
    thread,
};

// LU-START-1
#[test]
fn a_start_writes_a_pending_receipt_before_it_posts() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());

    let record = client.start(request("delivery:1")).unwrap();

    assert_eq!(uuid_version(&record.id), Some('4'));
    assert_eq!(record.activity_key, "delivery:1");
    assert_eq!(record.schema_version, 1);
    assert_eq!(record.template, Template::Progress);
    assert_eq!(record.snapshot, snapshot(0));
    assert_eq!(record.state, ActivityState::Active);
    assert_eq!(record.visibility, Visibility::Unknown);
    assert_eq!(
        driver.take_calls(),
        [
            "capabilities",
            "read",
            "observe",
            "write",
            "post",
            "observe",
            "write",
            "read"
        ]
    );
    let history = driver.edit(|state| state.history.clone());
    assert!(history[0][0].post_failed && history[0][0].awaiting_observation);
    let stored = &driver.records()[0];
    assert!(!stored.post_failed && !stored.awaiting_observation);
}

// LU-START-2, LU-START-4, LU-END-2
#[test]
fn duplicate_start_and_end_are_idempotent_without_reusing_handles() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());

    let first = client.start(request("delivery:1")).unwrap();
    assert_eq!(client.start(request("delivery:1")).unwrap().id, first.id);
    assert_eq!(driver.counts().0, 1);
    assert_eq!(
        client.end(&first.id, EndPolicy::Immediate).unwrap().state,
        ActivityState::Ended
    );
    let again = client.end(&first.id, EndPolicy::Immediate).unwrap();
    assert_eq!(again.state, ActivityState::Ended);
    assert_eq!(driver.counts().1, 1);

    let second = client.start(request("delivery:1")).unwrap();
    assert_ne!(second.id, first.id);
    assert_code(client.update(&first.id, snapshot(1)), ErrorCode::NotFound);

    client.end(&second.id, EndPolicy::Immediate).unwrap();
    driver.dismiss(&second.id);
    let third = client.start(request("delivery:1")).unwrap();
    assert!(third.id != first.id && third.id != second.id);
}

// LU-START-3
#[test]
fn a_different_request_under_a_live_key_conflicts() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    client.start(request("delivery:1")).unwrap();

    let mut title = request("delivery:1");
    title.snapshot.title = "Other".into();
    let mut fallback = request("delivery:1");
    fallback.fallback = Fallback::None;
    let mut visibility = request("delivery:1");
    visibility.visibility = Visibility::Public;
    let mut timer = request("delivery:1");
    timer.template = Template::Timer;
    timer.snapshot = timer_snapshot(0);
    for conflicting in [title, fallback, visibility, timer] {
        assert_code(client.start(conflicting), ErrorCode::Conflict);
    }
    assert_eq!(driver.counts().0, 1);
}

// LU-START-5
#[test]
fn posting_failures_leave_retryable_receipts() {
    let driver = FakeDriver::default();
    driver.edit(|state| state.fail_next_post = true);
    let client = LiveUpdate::new(driver.clone());
    assert_code(client.start(request("delivery:1")), ErrorCode::NativeError);
    let pending = driver.records()[0].clone();
    assert!(pending.post_failed);

    let mut other = request("delivery:1");
    other.snapshot.status = "Other".into();
    assert_code(client.start(other), ErrorCode::Conflict);

    let recovered = client.start(request("delivery:1")).unwrap();
    assert_eq!(recovered.id, pending.id);
    assert_eq!(recovered.state, ActivityState::Active);
    assert_eq!(driver.counts().0, 2);
    assert!(!driver.records()[0].post_failed);
}

// LU-START-6
#[test]
fn start_checks_input_then_permission_then_the_key_then_fallback_then_the_limit() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    driver.edit(|state| state.capabilities.notifications_enabled = false);
    assert_code(client.start(request(" invalid")), ErrorCode::InvalidInput);
    assert_code(
        client.start(request("delivery:1")),
        ErrorCode::PermissionDenied,
    );

    driver.edit(|state| {
        state.capabilities.notifications_enabled = true;
        state.capabilities.promotion_enabled = true;
    });
    let mut promoted = request("delivery:1");
    promoted.fallback = Fallback::None;
    let started = client.start(promoted.clone()).unwrap();

    driver.edit(|state| state.capabilities.notifications_enabled = false);
    assert_code(client.start(promoted.clone()), ErrorCode::PermissionDenied);

    // An existing activity answers before the fallback and the limit are looked at.
    driver.edit(|state| {
        state.capabilities.notifications_enabled = true;
        state.capabilities.promotion_enabled = false;
    });
    assert_eq!(client.start(promoted.clone()).unwrap().id, started.id);
    for index in 1..32 {
        client.start(request(&format!("activity:{index}"))).unwrap();
    }
    assert_eq!(client.start(promoted).unwrap().id, started.id);
    let mut unsupported = request("overflow:1");
    unsupported.fallback = Fallback::None;
    assert_code(client.start(unsupported), ErrorCode::UnsupportedFeature);
    assert_code(client.start(request("overflow:2")), ErrorCode::SystemLimit);
}

// LU-START-7
#[test]
fn receipts_and_keys_survive_a_new_client() {
    let driver = FakeDriver::default();
    let started = LiveUpdate::new(driver.clone())
        .start(request("delivery:1"))
        .unwrap();

    let restarted = LiveUpdate::new(driver.clone());
    assert_eq!(
        restarted.start(request("delivery:1")).unwrap().id,
        started.id
    );
    assert_eq!(
        restarted.update(&started.id, snapshot(1)).unwrap().id,
        started.id
    );
    assert_eq!(driver.counts().0, 2);
}

// LU-UPDATE-1, LU-UPDATE-8
#[test]
fn a_newer_revision_replaces_the_content() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();

    let mut next = snapshot(5);
    next.status = "On the way".into();
    next.progress = Some(0.5);
    let updated = client.update(&started.id, next.clone()).unwrap();

    assert_eq!(updated.id, started.id);
    assert_eq!(updated.snapshot, next);
    assert_eq!(updated.state, ActivityState::Active);
    assert_eq!(driver.records()[0].snapshot, next);
    assert_eq!(driver.records()[0].origin, request("delivery:1"));
    assert_eq!(driver.counts().0, 2);
}

// LU-UPDATE-2, LU-UPDATE-3, LU-UPDATE-4, LU-UPDATE-5
#[test]
fn revisions_reject_stale_conflicting_and_template_changing_updates() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let first = client.start(request("delivery:1")).unwrap();

    let mut accepted = snapshot(2);
    accepted.progress = Some(0.5);
    client.update(&first.id, accepted.clone()).unwrap();
    assert_code(
        client.update(&first.id, snapshot(1)),
        ErrorCode::StaleRevision,
    );
    let mut conflict = accepted.clone();
    conflict.progress = Some(0.7);
    assert_code(client.update(&first.id, conflict), ErrorCode::Conflict);
    assert_code(
        client.update(
            &first.id,
            Snapshot {
                revision: 3,
                progress: None,
                timer: Some(Timer {
                    started_at: 1,
                    ends_at: 2,
                }),
                ..snapshot(3)
            },
        ),
        ErrorCode::InvalidInput,
    );
    assert_eq!(
        client.update(&first.id, accepted.clone()).unwrap().snapshot,
        accepted
    );
    assert_eq!(driver.counts().0, 2);
}

// LU-UPDATE-3
#[test]
fn the_same_revision_retries_a_failed_post() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();
    driver.edit(|state| state.fail_next_post = true);
    assert_code(
        client.update(&started.id, snapshot(1)),
        ErrorCode::NativeError,
    );
    assert!(driver.records()[0].post_failed);

    let retried = client.update(&started.id, snapshot(1)).unwrap();
    assert_eq!(retried.snapshot.revision, 1);
    assert_eq!(retried.state, ActivityState::Active);
    assert_eq!(driver.counts().0, 3);
    client.update(&started.id, snapshot(1)).unwrap();
    assert_eq!(driver.counts().0, 3);
}

// LU-UPDATE-6
#[test]
fn updates_of_unknown_ended_or_dismissed_activities_are_not_found() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    assert_code(client.update("missing", snapshot(1)), ErrorCode::NotFound);
    for invalid in ["", " \t", &"x".repeat(257)] {
        assert_code(client.update(invalid, snapshot(1)), ErrorCode::InvalidInput);
    }

    let ended = client.start(request("delivery:1")).unwrap();
    client.end(&ended.id, EndPolicy::Immediate).unwrap();
    assert_code(client.update(&ended.id, snapshot(1)), ErrorCode::NotFound);

    let dismissed = client.start(request("delivery:2")).unwrap();
    driver.dismiss(&dismissed.id);
    assert_code(
        client.update(&dismissed.id, snapshot(1)),
        ErrorCode::NotFound,
    );
    assert_eq!(driver.counts().0, 2);
}

// LU-UPDATE-7
#[test]
fn updates_need_notifications_and_keep_the_promotion_of_the_start() {
    let driver = FakeDriver::default();
    driver.edit(|state| state.capabilities.promotion_enabled = true);
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();
    assert!(started.promotion_requested);

    driver.edit(|state| state.capabilities.notifications_enabled = false);
    assert_code(
        client.update(&started.id, snapshot(1)),
        ErrorCode::PermissionDenied,
    );
    assert_eq!(
        client.update(&started.id, snapshot(0)).unwrap().id,
        started.id
    );

    driver.edit(|state| {
        state.capabilities.notifications_enabled = true;
        state.capabilities.promotion_enabled = false;
    });
    let updated = client.update(&started.id, snapshot(1)).unwrap();
    assert!(updated.promotion_requested);
    assert_eq!(updated.promotion_state, PromotionState::Promoted);
}

// LU-END-1
#[test]
fn end_stores_the_ended_record_then_removes_the_notification() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    for (key, policy) in [
        ("delivery:1", EndPolicy::Immediate),
        ("delivery:2", EndPolicy::Default),
    ] {
        let started = client.start(request(key)).unwrap();
        driver.take_calls();

        let ended = client.end(&started.id, policy).unwrap();

        assert_eq!(ended.id, started.id);
        assert_eq!(ended.state, ActivityState::Ended);
        assert_eq!(driver.take_calls(), ["read", "observe", "write", "cancel"]);
        assert!(driver.edit(|state| !state.observed.contains_key(&started.id)));
    }
}

// LU-END-1
#[test]
fn an_ended_record_no_longer_awaits_a_post() {
    let driver = FakeDriver::default();
    driver.edit(|state| state.delay_observation = true);
    let client = LiveUpdate::new(driver.clone());
    let unseen = client.start(request("delivery:1")).unwrap();
    driver.edit(|state| state.fail_next_post = true);
    assert_code(client.start(request("delivery:2")), ErrorCode::NativeError);
    let failed = driver.records()[1].clone();
    assert!(driver.records()[0].awaiting_observation && failed.post_failed);

    for id in [&unseen.id, &failed.id] {
        client.end(id, EndPolicy::Immediate).unwrap();
    }

    for record in driver.records() {
        assert_eq!(record.state, ActivityState::Ended);
        assert!(
            !record.awaiting_observation && !record.post_failed,
            "{record:?}"
        );
    }
}

// LU-END-3
#[test]
fn a_delayed_end_needs_an_adapter_that_offers_it() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    assert_code(
        client.end("missing", EndPolicy::After { at: 1 }),
        ErrorCode::NotFound,
    );
    let started = client.start(request("delivery:1")).unwrap();

    assert_code(
        client.end(&started.id, EndPolicy::After { at: MAX_TIMESTAMP }),
        ErrorCode::UnsupportedFeature,
    );
    assert_code(
        client.end(
            &started.id,
            EndPolicy::After {
                at: MAX_TIMESTAMP + 1,
            },
        ),
        ErrorCode::InvalidInput,
    );
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Active);
    assert_eq!(driver.counts().1, 0);
}

// LU-END-4
#[test]
fn ending_a_dismissed_activity_marks_it_ended() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();
    driver.dismiss(&started.id);
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Dismissed);

    let ended = client.end(&started.id, EndPolicy::Immediate).unwrap();
    assert_eq!(ended.state, ActivityState::Ended);
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Ended);
}

// LU-END-5
#[test]
fn a_failed_cancel_is_repeated_by_the_next_operation() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();
    driver.edit(|state| state.fail_next_cancel = true);

    assert_code(
        client.end(&started.id, EndPolicy::Immediate),
        ErrorCode::NativeError,
    );
    assert_eq!(driver.records()[0].state, ActivityState::Ended);
    assert!(driver.edit(|state| state.observed.contains_key(&started.id)));

    assert_eq!(client.list().unwrap()[0].state, ActivityState::Ended);
    assert!(driver.edit(|state| !state.observed.contains_key(&started.id)));
    assert_eq!(driver.counts().1, 2);
}

// LU-END-6
#[test]
fn end_needs_a_known_valid_id() {
    let client = LiveUpdate::new(FakeDriver::default());
    assert_code(
        client.end("missing", EndPolicy::Immediate),
        ErrorCode::NotFound,
    );
    for invalid in ["", "\u{3000}", &"x".repeat(257)] {
        assert_code(
            client.end(invalid, EndPolicy::Immediate),
            ErrorCode::InvalidInput,
        );
    }
}

// LU-VALID-11
#[test]
fn invalid_requests_never_reach_the_driver() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();
    driver.take_calls();

    let mut invalid = request("delivery:2");
    invalid.schema_version = 2;
    assert_code(client.start(invalid), ErrorCode::InvalidInput);
    let mut nan = snapshot(1);
    nan.progress = Some(f64::NAN);
    assert_code(client.update(&started.id, nan), ErrorCode::InvalidInput);
    assert_code(client.update("", snapshot(1)), ErrorCode::InvalidInput);
    assert_code(
        client.end(
            &started.id,
            EndPolicy::After {
                at: MAX_TIMESTAMP + 1,
            },
        ),
        ErrorCode::InvalidInput,
    );

    assert!(driver.take_calls().is_empty());
}

// LU-CLIENT-1
#[test]
fn concurrent_operations_of_one_client_run_one_at_a_time() {
    let driver = FakeDriver::default();
    let client = Arc::new(LiveUpdate::new(driver.clone()));

    let same_key: Vec<_> = (0..8)
        .map(|_| {
            let client = Arc::clone(&client);
            thread::spawn(move || client.start(request("delivery:1")).unwrap().id)
        })
        .collect();
    let ids: Vec<_> = same_key.into_iter().map(|t| t.join().unwrap()).collect();
    assert!(ids.iter().all(|id| *id == ids[0]));
    assert_eq!(driver.counts().0, 1);

    let distinct: Vec<_> = (0..8)
        .map(|index| {
            let client = Arc::clone(&client);
            thread::spawn(move || {
                let started = client.start(request(&format!("other:{index}"))).unwrap();
                client.update(&started.id, snapshot(1)).unwrap();
                client.end(&started.id, EndPolicy::Immediate).unwrap();
            })
        })
        .collect();
    for thread in distinct {
        thread.join().unwrap();
    }
    let records = client.list().unwrap();
    assert_eq!(records.len(), 9);
    assert_eq!(
        records
            .iter()
            .filter(|record| record.state == ActivityState::Ended)
            .count(),
        8
    );
}

// LU-CLIENT-2
#[test]
fn a_driver_panic_does_not_disable_the_client() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    driver.edit(|state| state.panic_next_capabilities = true);

    assert!(catch_unwind(AssertUnwindSafe(|| client.capabilities())).is_err());

    assert!(client.capabilities().unwrap().can_post());
    client.start(request("delivery:1")).unwrap();
}

// LU-ERR-2
#[test]
fn error_messages_never_repeat_notification_content() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let mut secret = request("delivery:1");
    secret.snapshot.title = "Secret title".into();
    secret.snapshot.status = "Secret status".into();
    let started = client.start(secret.clone()).unwrap();

    let mut conflict = secret.clone();
    conflict.snapshot.progress = Some(1.0);
    let mut same_revision = secret.snapshot.clone();
    same_revision.status = "Secret other".into();
    let mut invalid = secret.clone();
    invalid.snapshot.title = "Secret".repeat(40);
    let errors = [
        client.start(conflict).unwrap_err(),
        client.update(&started.id, same_revision).unwrap_err(),
        client.start(invalid).unwrap_err(),
        client
            .end(&started.id, EndPolicy::After { at: 1 })
            .unwrap_err(),
    ];
    for error in errors {
        assert!(!error.message.contains("Secret"), "{error}");
        assert!(!error.to_string().contains("Secret"), "{error}");
    }
}

fn uuid_version(id: &str) -> Option<char> {
    let groups: Vec<_> = id.split('-').collect();
    let shaped = groups.iter().map(|group| group.len()).eq([8, 4, 4, 4, 12])
        && id.chars().all(|c| c == '-' || c.is_ascii_hexdigit());
    shaped.then(|| groups[2].chars().next()).flatten()
}

// LU-START-8
#[test]
fn a_start_the_adapter_refuses_as_invalid_leaves_no_receipt() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    driver.edit(|state| state.refuse_next_post = true);

    assert_code(client.start(request("delivery:1")), ErrorCode::InvalidInput);

    assert_eq!(
        driver.take_calls(),
        ["capabilities", "read", "observe", "write", "post", "write"]
    );
    assert!(driver.records().is_empty());
    assert!(client.list().unwrap().is_empty());

    // The other receipts, live and ended, are what they were.
    let live = client.start(request("delivery:live")).unwrap();
    let ended = client.start(request("delivery:ended")).unwrap();
    client.end(&ended.id, EndPolicy::Immediate).unwrap();
    let others = driver.records();
    driver.edit(|state| state.refuse_next_post = true);
    assert_code(client.start(request("delivery:2")), ErrorCode::InvalidInput);
    assert_eq!(driver.records(), others);
    assert_eq!(others[0].id, live.id);

    // The key is free for the corrected request.
    let mut corrected = request("delivery:1");
    corrected.snapshot.title = "Corrected".into();
    let posts = driver.counts().0;
    let started = client.start(corrected).unwrap();
    assert_eq!(started.state, ActivityState::Active);
    assert_eq!(started.snapshot.title, "Corrected");
    assert_eq!(driver.counts().0, posts + 1);
}

// LU-START-8
#[test]
fn a_retried_start_the_adapter_then_refuses_is_removed_unless_it_was_updated() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    driver.edit(|state| state.fail_next_post = true);
    assert_code(client.start(request("delivery:1")), ErrorCode::NativeError);
    assert_eq!(driver.records().len(), 1);

    // A retry that fails again in another way is no refusal: the receipt stays for the next one.
    driver.edit(|state| state.fail_next_post = true);
    assert_code(client.start(request("delivery:1")), ErrorCode::NativeError);
    assert_eq!(driver.records().len(), 1);
    assert_eq!(driver.counts().1, 0);

    // The earlier post may have reached Android without its answer: its notification is cancelled
    // before the receipt goes.
    driver.edit(|state| state.refuse_next_post = true);
    driver.take_calls();
    assert_code(client.start(request("delivery:1")), ErrorCode::InvalidInput);
    assert_eq!(
        driver.take_calls(),
        [
            "capabilities",
            "read",
            "observe",
            "write",
            "post",
            "write",
            "cancel",
            "write"
        ]
    );
    assert!(driver.records().is_empty());

    // The same update of a start that was never shown is such a retry too.
    driver.edit(|state| state.fail_next_post = true);
    assert_code(client.start(request("delivery:1")), ErrorCode::NativeError);
    let pending = driver.records()[0].clone();
    driver.edit(|state| state.refuse_next_post = true);
    assert_code(
        client.update(&pending.id, pending.snapshot.clone()),
        ErrorCode::InvalidInput,
    );
    assert!(driver.records().is_empty());
    assert_eq!(driver.counts().1, 2);

    // A cancel that fails keeps the receipt, so that the same start comes here again.
    driver.edit(|state| state.fail_next_post = true);
    assert_code(client.start(request("delivery:1")), ErrorCode::NativeError);
    driver.edit(|state| {
        state.refuse_next_post = true;
        state.fail_next_cancel = true;
    });
    assert_code(client.start(request("delivery:1")), ErrorCode::NativeError);
    assert_eq!(driver.records().len(), 1);
    driver.edit(|state| state.refuse_next_post = true);
    assert_code(client.start(request("delivery:1")), ErrorCode::InvalidInput);
    assert!(driver.records().is_empty());

    // An activity whose update failed keeps its receipt when the retry is refused: the content
    // before that update is no longer known.
    let started = client.start(request("delivery:2")).unwrap();
    driver.edit(|state| state.fail_next_post = true);
    assert_code(
        client.update(&started.id, snapshot(1)),
        ErrorCode::NativeError,
    );
    driver.edit(|state| state.refuse_next_post = true);
    assert_code(client.start(request("delivery:2")), ErrorCode::InvalidInput);
    let kept = driver.records();
    assert_eq!(kept.len(), 1);
    assert_eq!(kept[0].snapshot.revision, 1);
    assert!(kept[0].post_failed);
    assert_eq!(
        client.update(&started.id, snapshot(2)).unwrap().state,
        ActivityState::Active
    );
}

// LU-UPDATE-9
#[test]
fn an_update_the_adapter_refuses_as_invalid_restores_the_accepted_snapshot() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();
    let mut refused = snapshot(4);
    refused.title = "Refused".into();
    driver.edit(|state| state.refuse_next_post = true);
    driver.take_calls();

    assert_code(client.update(&started.id, refused), ErrorCode::InvalidInput);

    assert_eq!(
        driver.take_calls(),
        ["read", "observe", "capabilities", "write", "post", "write"]
    );
    let stored = driver.records();
    assert_eq!(stored[0].snapshot, snapshot(0));
    assert_eq!(stored[0].state, ActivityState::Active);
    assert!(!stored[0].post_failed);
    assert!(!stored[0].awaiting_observation);
    assert_eq!(client.list().unwrap()[0].snapshot, snapshot(0));

    // The revision is free for the corrected content.
    let mut corrected = snapshot(4);
    corrected.title = "Corrected".into();
    let updated = client.update(&started.id, corrected.clone()).unwrap();
    assert_eq!(updated.snapshot, corrected);
    assert_eq!(updated.state, ActivityState::Active);
    assert_eq!(driver.counts().0, 3);

    // An update that Android does not list yet comes back as it was, still awaited, and the other
    // receipts are what they were.
    let ended = client.start(request("delivery:ended")).unwrap();
    client.end(&ended.id, EndPolicy::Immediate).unwrap();
    driver.edit(|state| state.delay_observation = true);
    client.update(&started.id, snapshot(5)).unwrap();
    let awaited = driver.records();
    assert!(awaited[0].awaiting_observation);
    driver.edit(|state| state.refuse_next_post = true);
    assert_code(
        client.update(&started.id, snapshot(6)),
        ErrorCode::InvalidInput,
    );
    assert_eq!(driver.records(), awaited);
    assert_eq!(client.list().unwrap()[0].snapshot, snapshot(5));
}

// LU-START-8
#[test]
fn a_refusal_whose_receipts_cannot_be_restored_is_met_again() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    driver.edit(|state| {
        state.refuse_next_post = true;
        state.fail_write_after = Some(1);
    });

    // The pending receipt was written, the restoring write failed: that failure is the answer.
    assert_code(client.start(request("delivery:1")), ErrorCode::NativeError);
    assert_eq!(driver.records().len(), 1);
    assert!(driver.records()[0].post_failed);

    // The same start is refused again, and this time the receipt goes.
    driver.edit(|state| state.refuse_next_post = true);
    assert_code(client.start(request("delivery:1")), ErrorCode::InvalidInput);
    assert!(driver.records().is_empty());
}
