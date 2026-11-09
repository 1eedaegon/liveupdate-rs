//! Reconciliation with Android's active notifications, the bounds on stored records, and the
//! capability and fallback decisions of a start.

mod common;

use common::{FakeDriver, assert_code, request, snapshot, timer_snapshot};
use liveupdate::{
    ActivityState, Capabilities, EndPolicy, ErrorCode, Fallback, LiveUpdate, Observation,
    PromotionState,
};

// LU-LIST-1
#[test]
fn list_returns_active_and_terminal_records() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let active = client.start(request("delivery:1")).unwrap();
    let ended = client.start(request("delivery:2")).unwrap();
    client.end(&ended.id, EndPolicy::Immediate).unwrap();
    let dismissed = client.start(request("delivery:3")).unwrap();
    driver.dismiss(&dismissed.id);

    let listed: Vec<_> = client
        .list()
        .unwrap()
        .into_iter()
        .map(|record| (record.id, record.state))
        .collect();

    assert_eq!(
        listed,
        [
            (active.id, ActivityState::Active),
            (ended.id, ActivityState::Ended),
            (dismissed.id, ActivityState::Dismissed),
        ]
    );
}

// LU-LIST-2, LU-CAP-4
#[test]
fn an_observation_sets_the_state_and_the_promotion_of_a_record() {
    let driver = FakeDriver::default();
    driver.edit(|state| {
        state.capabilities.promotion_enabled = true;
        state.promotion_outcome = PromotionState::NotPromoted;
    });
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();
    assert!(started.promotion_requested);
    assert_eq!(started.promotion_state, PromotionState::NotPromoted);
    assert_eq!(started.degraded_features, ["nativeLiveActivity"]);

    driver.edit(|state| {
        let observed = state.observed.get_mut(&started.id).unwrap();
        observed.state = ActivityState::Stale;
        observed.promotion_state = PromotionState::Promoted;
    });
    let listed = &client.list().unwrap()[0];
    assert_eq!(listed.state, ActivityState::Stale);
    assert_eq!(listed.promotion_state, PromotionState::Promoted);
    assert!(listed.degraded_features.is_empty());
    let stored = &driver.records()[0];
    assert!(!stored.awaiting_observation && !stored.post_failed);
}

// LU-LIST-3, LU-START-7
#[test]
fn durable_records_reconcile_after_restart_and_observed_dismissal() {
    let driver = FakeDriver::default();
    let first = LiveUpdate::new(driver.clone())
        .start(request("delivery:1"))
        .unwrap();
    let restarted = LiveUpdate::new(driver.clone());
    assert_eq!(restarted.list().unwrap()[0].id, first.id);

    driver.edit(|state| state.observed.clear());
    assert_eq!(restarted.list().unwrap()[0].state, ActivityState::Dismissed);
    assert_eq!(driver.records()[0].state, ActivityState::Dismissed);
    assert_code(
        restarted.update(&first.id, snapshot(1)),
        ErrorCode::NotFound,
    );
}

// LU-LIST-4
#[test]
fn a_record_android_has_not_shown_yet_stays_unknown() {
    let driver = FakeDriver::default();
    driver.edit(|state| state.delay_observation = true);
    let client = LiveUpdate::new(driver.clone());
    let pending = client.start(request("delivery:1")).unwrap();
    assert_eq!(pending.state, ActivityState::Unknown);
    assert_eq!(pending.promotion_state, PromotionState::NotApplicable);

    for _ in 0..2 {
        assert_eq!(client.list().unwrap()[0].state, ActivityState::Unknown);
    }
    assert_eq!(
        client.update(&pending.id, snapshot(1)).unwrap().state,
        ActivityState::Unknown
    );
}

// LU-LIST-5
#[test]
fn delayed_observation_preserves_accepted_revisions() {
    let driver = FakeDriver::default();
    driver.edit(|state| {
        state.delay_observation = true;
        state.capabilities.promotion_enabled = true;
    });
    let client = LiveUpdate::new(driver.clone());
    let pending = client.start(request("delivery:1")).unwrap();
    assert_eq!(pending.state, ActivityState::Unknown);
    assert_eq!(pending.promotion_state, PromotionState::Unknown);
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Unknown);

    let shown = Observation {
        id: pending.id.clone(),
        snapshot: pending.snapshot.clone(),
        state: ActivityState::Active,
        promotion_requested: true,
        promotion_state: PromotionState::Promoted,
    };
    driver.edit(|state| state.observed.insert(pending.id.clone(), shown.clone()));
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Active);

    let mut next = snapshot(1);
    next.progress = Some(0.5);
    let updated = client.update(&pending.id, next).unwrap();
    assert_eq!(updated.snapshot.revision, 1);
    // Android still shows revision 0: the accepted revision 1 stays.
    let listed = &client.list().unwrap()[0];
    assert_eq!(listed.snapshot.revision, 1);
    assert_eq!(listed.state, ActivityState::Unknown);
}

// LU-LIST-6
#[test]
fn observations_that_contradict_the_receipt_fail_closed() {
    let contradictions: [fn(&mut Observation); 5] = [
        |observed| observed.snapshot.revision = 99,
        |observed| observed.snapshot.title = "Other".into(),
        |observed| observed.snapshot = timer_snapshot(0),
        |observed| observed.promotion_requested = true,
        |observed| observed.promotion_state = PromotionState::Promoted,
    ];
    for contradict in contradictions {
        let driver = FakeDriver::default();
        let client = LiveUpdate::new(driver.clone());
        let started = client.start(request("delivery:1")).unwrap();
        let stored = driver.records();
        driver.edit(|state| contradict(state.observed.get_mut(&started.id).unwrap()));

        assert_code(client.list(), ErrorCode::NativeError);
        assert_code(
            client.update(&started.id, snapshot(1)),
            ErrorCode::NativeError,
        );
        assert_eq!(driver.records(), stored);
    }
}

// LU-LIST-7
#[test]
fn visible_notifications_of_ended_activities_are_cancelled() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();
    let shown = driver.edit(|state| state.observed[&started.id].clone());
    client.end(&started.id, EndPolicy::Immediate).unwrap();
    driver.edit(|state| state.observed.insert(started.id.clone(), shown));

    assert_eq!(client.list().unwrap()[0].state, ActivityState::Ended);
    assert!(driver.edit(|state| state.observed.is_empty()));
    assert_eq!(driver.counts().1, 2);
}

// LU-LIST-8
#[test]
fn notifications_without_a_receipt_are_ignored() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();
    let mut orphan = driver.edit(|state| state.observed[&started.id].clone());
    orphan.id = "orphan".into();
    driver.edit(|state| state.observed.insert("orphan".into(), orphan));

    let listed = client.list().unwrap();
    assert_eq!(listed.len(), 1);
    assert_eq!(listed[0].id, started.id);
    assert!(driver.edit(|state| state.observed.contains_key("orphan")));
    assert_eq!(driver.counts().1, 0);
}

// LU-LIST-9
#[test]
fn list_writes_only_when_reconciliation_changes_a_record() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();
    let writes = driver.edit(|state| state.writes);

    client.list().unwrap();
    assert_eq!(driver.edit(|state| state.writes), writes);
    driver.dismiss(&started.id);
    client.list().unwrap();
    client.list().unwrap();
    assert_eq!(driver.edit(|state| state.writes), writes + 1);
}

// LU-LIMIT-1
#[test]
fn active_and_terminal_records_are_bounded() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    for index in 0..32 {
        client.start(request(&format!("activity:{index}"))).unwrap();
    }
    assert_code(client.start(request("overflow")), ErrorCode::SystemLimit);
    assert_eq!(driver.counts().0, 32);
    let first = &client.list().unwrap()[0];
    assert_eq!(client.start(request("activity:0")).unwrap().id, first.id);
    for record in client.list().unwrap() {
        client.end(&record.id, EndPolicy::Immediate).unwrap();
    }
    for index in 0..140 {
        let record = client
            .start(request(&format!("completed:{index}")))
            .unwrap();
        client.end(&record.id, EndPolicy::Immediate).unwrap();
    }
    assert_eq!(driver.records().len(), 128);
    client.start(request("after")).unwrap();
    assert_eq!(driver.records().len(), 129);
}

// LU-LIMIT-2
#[test]
fn the_oldest_tombstone_is_evicted_first() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let mut ended = Vec::new();
    for index in 0..128 {
        let record = client.start(request(&format!("done:{index}"))).unwrap();
        client.end(&record.id, EndPolicy::Immediate).unwrap();
        ended.push(record.id);
    }
    let dismissed = client.start(request("dismissed")).unwrap();
    driver.dismiss(&dismissed.id);
    client.list().unwrap();
    let last = client.start(request("last")).unwrap();

    client.end(&last.id, EndPolicy::Immediate).unwrap();

    let kept: Vec<_> = client.list().unwrap().into_iter().map(|r| r.id).collect();
    assert_eq!(kept.len(), 128);
    assert_eq!(kept[126..], [dismissed.id.clone(), last.id.clone()]);
    assert_eq!(kept[..126], ended[2..]);
    assert_eq!(
        client.end(&last.id, EndPolicy::Immediate).unwrap().state,
        ActivityState::Ended
    );
    assert_code(
        client.end(&ended[0], EndPolicy::Immediate),
        ErrorCode::NotFound,
    );
}

// LU-LIMIT-3
#[test]
fn corrupt_driver_records_fail_closed() {
    let corruptions: [fn(&mut Vec<liveupdate::DriverRecord>); 6] = [
        |records| {
            let mut extra = records[0].clone();
            extra.id = "unexpected-extra-id".into();
            extra.origin.activity_key = "activity:extra".into();
            records.push(extra);
        },
        |records| {
            let duplicate = records[0].clone();
            records[1] = duplicate;
        },
        |records| records[0].id = " ".into(),
        |records| records[0].origin.schema_version = 2,
        |records| records[0].snapshot = timer_snapshot(0),
        |records| records[0].snapshot.progress = Some(2.0),
    ];
    for corrupt in corruptions {
        let driver = FakeDriver::default();
        let client = LiveUpdate::new(driver.clone());
        for index in 0..32 {
            client.start(request(&format!("activity:{index}"))).unwrap();
        }
        driver.edit(|state| corrupt(&mut state.records));
        let stored = driver.records();

        assert_code(client.list(), ErrorCode::NativeError);
        assert_code(client.start(request("other")), ErrorCode::NativeError);
        assert_eq!(driver.records(), stored);
    }

    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("activity:0")).unwrap();
    client.end(&started.id, EndPolicy::Immediate).unwrap();
    driver.edit(|state| {
        let tombstone = state.records[0].clone();
        state.records = (0..129)
            .map(|index| liveupdate::DriverRecord {
                id: format!("tombstone-{index}"),
                ..tombstone.clone()
            })
            .collect();
    });
    assert_code(client.list(), ErrorCode::NativeError);
}

// LU-LIMIT-4
#[test]
fn ended_notifications_android_still_lists_do_not_block_the_active_limit() {
    let driver = FakeDriver::default();
    driver.edit(|state| state.cancel_linger = 2);
    let client = LiveUpdate::new(driver.clone());
    let mut started = Vec::new();
    for index in 0..32 {
        started.push(client.start(request(&format!("activity:{index}"))).unwrap());
    }
    client.end(&started[0].id, EndPolicy::Immediate).unwrap();

    // Android still lists the cancelled notification next to 32 active ones.
    let replacement = client.start(request("replacement")).unwrap();

    assert_eq!(replacement.state, ActivityState::Active);
    assert_eq!(client.list().unwrap().len(), 33);
}

// LU-FALLBACK-2, LU-CAP-4
#[test]
fn notification_permission_and_explicit_fallback_are_enforced() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    driver.edit(|state| state.capabilities.notifications_enabled = false);
    assert_code(
        client.start(request("delivery:1")),
        ErrorCode::PermissionDenied,
    );

    driver.edit(|state| state.capabilities.notifications_enabled = true);
    let mut promotion_only = request("delivery:1");
    promotion_only.fallback = Fallback::None;
    driver.take_calls();
    assert_code(
        client.start(promotion_only.clone()),
        ErrorCode::UnsupportedFeature,
    );
    assert!(!driver.take_calls().contains(&"write"));
    assert_eq!(driver.counts().0, 0);

    driver.edit(|state| state.capabilities.promotion_enabled = true);
    let record = client.start(promotion_only).unwrap();
    assert!(record.promotion_requested);
    assert_eq!(record.promotion_state, PromotionState::Promoted);
    assert!(record.degraded_features.is_empty());
}

// LU-FALLBACK-1, LU-API-1
#[test]
fn without_promotion_the_fallback_posts_a_regular_notification() {
    for capabilities in [
        Capabilities {
            supported: false,
            notifications_enabled: true,
            promotion_enabled: false,
        },
        Capabilities {
            supported: true,
            notifications_enabled: true,
            promotion_enabled: false,
        },
    ] {
        let driver = FakeDriver::default();
        driver.edit(|state| state.capabilities = capabilities);
        let client = LiveUpdate::new(driver.clone());

        let record = client.start(request("delivery:1")).unwrap();

        assert!(!record.promotion_requested);
        assert_eq!(record.promotion_state, PromotionState::NotApplicable);
        assert_eq!(record.degraded_features, ["nativeLiveActivity"]);
        assert!(!driver.records()[0].promotion_requested);
        let mut strict = request("delivery:2");
        strict.fallback = Fallback::None;
        assert_code(client.start(strict), ErrorCode::UnsupportedFeature);
    }
}

// LU-FALLBACK-3
#[test]
fn promotion_is_requested_whenever_it_is_available() {
    for fallback in [Fallback::None, Fallback::Notification] {
        let driver = FakeDriver::default();
        driver.edit(|state| {
            state.capabilities.promotion_enabled = true;
            state.promotion_outcome = PromotionState::NotPromoted;
        });
        let client = LiveUpdate::new(driver.clone());
        let mut started = request("delivery:1");
        started.fallback = fallback;

        let record = client.start(started).unwrap();

        assert!(record.promotion_requested);
        assert_eq!(record.promotion_state, PromotionState::NotPromoted);
        assert_eq!(record.degraded_features, ["nativeLiveActivity"]);
    }
}

// LU-CAP-1
#[test]
fn capabilities_come_from_the_driver_unchanged() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    for capabilities in [
        Capabilities {
            supported: true,
            notifications_enabled: false,
            promotion_enabled: true,
        },
        Capabilities {
            supported: false,
            notifications_enabled: true,
            promotion_enabled: false,
        },
    ] {
        driver.edit(|state| state.capabilities = capabilities);
        assert_eq!(client.capabilities().unwrap(), capabilities);
    }
    assert_eq!(driver.take_calls(), ["capabilities", "capabilities"]);
}

// LU-LIST-6
#[test]
fn a_failed_observation_fails_the_operation() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    client.start(request("delivery:1")).unwrap();
    driver.edit(|state| state.fail_next_observe = true);
    assert_code(client.list(), ErrorCode::NativeError);
    assert_eq!(client.list().unwrap().len(), 1);
}
