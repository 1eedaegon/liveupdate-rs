//! Native Rust lifecycle tests against a deterministic Android driver.

use liveupdate::{
    ActivityState, Capabilities, Driver, DriverRecord, EndPolicy, Error, ErrorCode, Fallback,
    LiveUpdate, Observation, PromotionState, Snapshot, StartRequest, Template, Timer, Visibility,
};
use std::{
    collections::BTreeMap,
    sync::{Arc, Mutex},
};

#[derive(Clone)]
struct FakeDriver {
    state: Arc<Mutex<FakeState>>,
}

struct FakeState {
    capabilities: Capabilities,
    records: Vec<DriverRecord>,
    observed: BTreeMap<String, Observation>,
    posts: usize,
    cancels: usize,
    delay_observation: bool,
    fail_next_post: bool,
}

impl Default for FakeDriver {
    fn default() -> Self {
        Self {
            state: Arc::new(Mutex::new(FakeState {
                capabilities: Capabilities {
                    supported: true,
                    notifications_enabled: true,
                    promotion_enabled: false,
                },
                records: Vec::new(),
                observed: BTreeMap::new(),
                posts: 0,
                cancels: 0,
                delay_observation: false,
                fail_next_post: false,
            })),
        }
    }
}

impl Driver for FakeDriver {
    fn capabilities(&self) -> liveupdate::Result<Capabilities> {
        Ok(self.state.lock().unwrap().capabilities)
    }

    fn read(&self) -> liveupdate::Result<Vec<DriverRecord>> {
        Ok(self.state.lock().unwrap().records.clone())
    }

    fn write(&self, records: &[DriverRecord]) -> liveupdate::Result<()> {
        self.state.lock().unwrap().records = records.to_vec();
        Ok(())
    }

    fn observe(&self) -> liveupdate::Result<Vec<Observation>> {
        Ok(self
            .state
            .lock()
            .unwrap()
            .observed
            .values()
            .cloned()
            .collect())
    }

    fn post(&self, record: &DriverRecord) -> liveupdate::Result<()> {
        let mut state = self.state.lock().unwrap();
        state.posts += 1;
        if state.fail_next_post {
            state.fail_next_post = false;
            return Err(Error::new(ErrorCode::NativeError, "Posting failed."));
        }
        if !state.delay_observation {
            let promotion_state = if state.capabilities.can_promote() {
                PromotionState::Promoted
            } else {
                PromotionState::NotApplicable
            };
            state.observed.insert(
                record.id.clone(),
                Observation {
                    id: record.id.clone(),
                    snapshot: record.snapshot.clone(),
                    state: ActivityState::Active,
                    promotion_requested: record.promotion_requested,
                    promotion_state,
                },
            );
        }
        Ok(())
    }

    fn cancel(&self, id: &str) -> liveupdate::Result<()> {
        let mut state = self.state.lock().unwrap();
        state.cancels += 1;
        state.observed.remove(id);
        Ok(())
    }
}

impl FakeDriver {
    fn edit(&self, action: impl FnOnce(&mut FakeState)) {
        action(&mut self.state.lock().unwrap());
    }

    fn records(&self) -> Vec<DriverRecord> {
        self.state.lock().unwrap().records.clone()
    }

    fn counts(&self) -> (usize, usize) {
        let state = self.state.lock().unwrap();
        (state.posts, state.cancels)
    }
}

fn snapshot(revision: u64) -> Snapshot {
    Snapshot {
        revision,
        title: "Delivery".into(),
        status: "Preparing".into(),
        progress: Some(0.0),
        timer: None,
        stale_at: None,
    }
}

fn request(key: &str) -> StartRequest {
    StartRequest {
        activity_key: key.into(),
        schema_version: 1,
        template: Template::Progress,
        fallback: Fallback::Notification,
        visibility: Visibility::Private,
        snapshot: snapshot(0),
    }
}

fn assert_code<T: std::fmt::Debug>(result: liveupdate::Result<T>, code: ErrorCode) {
    assert_eq!(result.unwrap_err().code, code);
}

#[test]
fn duplicate_start_and_end_are_idempotent_without_reusing_handles() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());

    let first = client.start(request("delivery:1")).unwrap();
    assert_eq!(first.visibility, Visibility::Unknown);
    assert_eq!(client.start(request("delivery:1")).unwrap().id, first.id);
    assert_eq!(driver.counts().0, 1);
    assert_eq!(
        client.end(&first.id, EndPolicy::Immediate).unwrap().state,
        ActivityState::Ended
    );
    client.end(&first.id, EndPolicy::Immediate).unwrap();
    assert_eq!(driver.counts().1, 1);

    let second = client.start(request("delivery:1")).unwrap();
    assert_ne!(second.id, first.id);
    assert_code(client.update(&first.id, snapshot(1)), ErrorCode::NotFound);
}

#[test]
fn revisions_reject_stale_conflicting_and_template_changing_updates() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let first = client.start(request("delivery:1")).unwrap();

    let mut conflicting_start = request("delivery:1");
    conflicting_start.snapshot.title = "Other".into();
    assert_code(client.start(conflicting_start), ErrorCode::Conflict);

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
    client.update(&first.id, accepted).unwrap();
    assert_eq!(driver.counts().0, 2);
}

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
    assert_code(
        client.start(promotion_only.clone()),
        ErrorCode::UnsupportedFeature,
    );

    driver.edit(|state| state.capabilities.promotion_enabled = true);
    let record = client.start(promotion_only).unwrap();
    assert_eq!(record.promotion_state, PromotionState::Promoted);
}

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
    assert_code(
        restarted.update(&first.id, snapshot(1)),
        ErrorCode::NotFound,
    );
}

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
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Unknown);

    driver.edit(|state| {
        state.observed.insert(
            pending.id.clone(),
            Observation {
                id: pending.id.clone(),
                snapshot: pending.snapshot.clone(),
                state: ActivityState::Active,
                promotion_requested: true,
                promotion_state: PromotionState::Promoted,
            },
        );
    });
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Active);

    let mut next = snapshot(1);
    next.progress = Some(0.5);
    assert_eq!(
        client.update(&pending.id, next).unwrap().snapshot.revision,
        1
    );
    assert_eq!(client.list().unwrap()[0].snapshot.revision, 1);
}

#[test]
fn posting_failures_leave_retryable_receipts() {
    let driver = FakeDriver::default();
    driver.edit(|state| state.fail_next_post = true);
    let client = LiveUpdate::new(driver.clone());
    assert_code(client.start(request("delivery:1")), ErrorCode::NativeError);
    let pending_id = driver.records()[0].id.clone();

    let recovered = client.start(request("delivery:1")).unwrap();
    assert_eq!(recovered.id, pending_id);
    assert_eq!(recovered.state, ActivityState::Active);
    assert_eq!(driver.counts().0, 2);
}

#[test]
fn active_and_terminal_records_are_bounded() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    for index in 0..32 {
        client.start(request(&format!("activity:{index}"))).unwrap();
    }
    assert_code(client.start(request("overflow")), ErrorCode::SystemLimit);
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
}

#[test]
fn request_validation_rejects_invalid_boundaries() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver);

    let mut invalid_key = request(" invalid");
    assert_code(client.start(invalid_key.clone()), ErrorCode::InvalidInput);
    invalid_key.activity_key = "valid".into();
    invalid_key.snapshot.progress = Some(f64::NAN);
    assert_code(client.start(invalid_key), ErrorCode::InvalidInput);

    let mut timer_request = request("timer:1");
    timer_request.template = Template::Timer;
    timer_request.snapshot.progress = None;
    timer_request.snapshot.timer = Some(Timer {
        started_at: 2,
        ends_at: 1,
    });
    assert_code(client.start(timer_request), ErrorCode::InvalidInput);
}

#[test]
fn corrupt_driver_records_cannot_bypass_active_limits() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    for index in 0..32 {
        client.start(request(&format!("activity:{index}"))).unwrap();
    }
    driver.edit(|state| {
        let mut extra = state.records[0].clone();
        extra.id = "unexpected-extra-id".into();
        extra.origin.activity_key = "activity:extra".into();
        state.records.push(extra);
    });

    assert_code(client.list(), ErrorCode::NativeError);
}

#[test]
fn driver_observations_cannot_invent_unaccepted_revisions() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();
    driver.edit(|state| {
        state
            .observed
            .get_mut(&started.id)
            .unwrap()
            .snapshot
            .revision = 99;
    });

    assert_code(client.list(), ErrorCode::NativeError);
    assert_eq!(driver.records()[0].snapshot.revision, 0);
}
