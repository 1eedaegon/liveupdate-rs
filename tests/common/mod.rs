//! A deterministic in-memory Android driver shared by the lifecycle tests.
//!
//! It behaves like the AAR's adapter: `post` makes a notification observable, `cancel` removes it,
//! and storage keeps exactly what `write` received. The post of an ended record is its final form,
//! which the adapter shows but no longer observes. Switches simulate what Android does on a real
//! device: a post that is not yet visible, a cancel that is still listed for a few reads, failures
//! and a panicking implementation.
#![allow(dead_code)]

use liveupdate::{
    ActivityState, Capabilities, Driver, DriverRecord, Error, ErrorCode, EventBatch, Fallback,
    Observation, PromotionState, Snapshot, StartReport, StartRequest, Template, Timer, Visibility,
};
use std::{
    collections::BTreeMap,
    sync::{Arc, Mutex},
};

#[derive(Clone)]
pub struct FakeDriver {
    state: Arc<Mutex<FakeState>>,
}

pub struct FakeState {
    pub capabilities: Capabilities,
    pub records: Vec<DriverRecord>,
    pub observed: BTreeMap<String, Observation>,
    /// The ended records posted in their final form, by id.
    pub finals: BTreeMap<String, DriverRecord>,
    /// Every record the driver was given to post, oldest first, also those it then refused.
    pub posted: Vec<DriverRecord>,
    /// Every driver call in order, by method name.
    pub calls: Vec<&'static str>,
    pub posts: usize,
    pub cancels: usize,
    pub writes: usize,
    /// Every list of records written, oldest first.
    pub history: Vec<Vec<DriverRecord>>,
    /// A post is accepted but Android does not list it yet.
    pub delay_observation: bool,
    pub fail_next_post: bool,
    /// The adapter refuses the next post as invalid, before it posts anything, as it does for content
    /// that only the device can check.
    pub refuse_next_post: bool,
    pub fail_next_cancel: bool,
    /// Fails one write after this many more have passed.
    pub fail_write_after: Option<usize>,
    pub fail_next_observe: bool,
    /// How many further `observe` calls still list a cancelled notification, as Android's
    /// asynchronous cancel does.
    pub cancel_linger: usize,
    lingering: BTreeMap<String, usize>,
    /// The promotion state Android reports for a post that requested promotion.
    pub promotion_outcome: PromotionState,
    pub panic_next_capabilities: bool,
    pub events: EventBatch,
    /// Every record the driver was given to schedule, oldest first, also those it then refused.
    pub scheduled: Vec<DriverRecord>,
    pub fail_next_schedule: bool,
    /// The adapter refuses the next scheduling as invalid, as it refuses a post.
    pub refuse_next_schedule: bool,
    /// What the adapter reports of the scheduled starts when it is asked.
    pub starts: Vec<StartReport>,
    pub fail_next_starts: bool,
}

impl Default for FakeDriver {
    fn default() -> Self {
        Self {
            state: Arc::new(Mutex::new(FakeState {
                capabilities: Capabilities {
                    supported: true,
                    notifications_enabled: true,
                    promotion_enabled: false,
                    presentation: Default::default(),
                },
                records: Vec::new(),
                observed: BTreeMap::new(),
                finals: BTreeMap::new(),
                posted: Vec::new(),
                calls: Vec::new(),
                posts: 0,
                cancels: 0,
                writes: 0,
                history: Vec::new(),
                delay_observation: false,
                fail_next_post: false,
                refuse_next_post: false,
                fail_next_cancel: false,
                fail_write_after: None,
                fail_next_observe: false,
                cancel_linger: 0,
                lingering: BTreeMap::new(),
                promotion_outcome: PromotionState::Promoted,
                panic_next_capabilities: false,
                events: EventBatch::default(),
                scheduled: Vec::new(),
                fail_next_schedule: false,
                refuse_next_schedule: false,
                starts: Vec::new(),
                fail_next_starts: false,
            })),
        }
    }
}

impl Driver for FakeDriver {
    fn capabilities(&self) -> liveupdate::Result<Capabilities> {
        let (capabilities, panic) = {
            let mut state = self.state.lock().unwrap();
            state.calls.push("capabilities");
            let panic = std::mem::take(&mut state.panic_next_capabilities);
            (state.capabilities, panic)
        };
        // Outside the fake's own lock, so only the client's lock sees the panic.
        assert!(!panic, "driver panicked");
        Ok(capabilities)
    }

    fn read(&self) -> liveupdate::Result<Vec<DriverRecord>> {
        let mut state = self.state.lock().unwrap();
        state.calls.push("read");
        Ok(state.records.clone())
    }

    fn write(&self, records: &[DriverRecord]) -> liveupdate::Result<()> {
        let mut state = self.state.lock().unwrap();
        state.calls.push("write");
        match state.fail_write_after {
            Some(0) => {
                state.fail_write_after = None;
                return Err(Error::new(ErrorCode::NativeError, "Writing failed."));
            }
            Some(remaining) => state.fail_write_after = Some(remaining - 1),
            None => {}
        }
        state.writes += 1;
        state.records = records.to_vec();
        state.history.push(records.to_vec());
        Ok(())
    }

    fn observe(&self) -> liveupdate::Result<Vec<Observation>> {
        let mut state = self.state.lock().unwrap();
        state.calls.push("observe");
        if std::mem::take(&mut state.fail_next_observe) {
            return Err(Error::new(ErrorCode::NativeError, "Observing failed."));
        }
        let observed = state.observed.values().cloned().collect();
        let mut gone = Vec::new();
        for (id, remaining) in &mut state.lingering {
            *remaining -= 1;
            if *remaining == 0 {
                gone.push(id.clone());
            }
        }
        for id in gone {
            state.lingering.remove(&id);
            state.observed.remove(&id);
        }
        Ok(observed)
    }

    fn post(&self, record: &DriverRecord) -> liveupdate::Result<()> {
        let mut state = self.state.lock().unwrap();
        state.calls.push("post");
        state.posts += 1;
        state.posted.push(record.clone());
        if std::mem::take(&mut state.fail_next_post) {
            return Err(Error::new(ErrorCode::NativeError, "Posting failed."));
        }
        if std::mem::take(&mut state.refuse_next_post) {
            return Err(Error::new(
                ErrorCode::InvalidInput,
                "Invalid live update request.",
            ));
        }
        if record.state == ActivityState::Ended {
            state.observed.remove(&record.id);
            state.finals.insert(record.id.clone(), record.clone());
            return Ok(());
        }
        if !state.delay_observation {
            let promotion_state = if record.promotion_requested {
                state.promotion_outcome
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
        state.calls.push("cancel");
        state.cancels += 1;
        if std::mem::take(&mut state.fail_next_cancel) {
            return Err(Error::new(ErrorCode::NativeError, "Cancelling failed."));
        }
        if state.cancel_linger > 0 && state.observed.contains_key(id) {
            let linger = state.cancel_linger;
            state.lingering.entry(id.into()).or_insert(linger);
        } else {
            state.observed.remove(id);
        }
        Ok(())
    }

    fn drain_events(&self) -> liveupdate::Result<EventBatch> {
        let mut state = self.state.lock().unwrap();
        state.calls.push("drain_events");
        Ok(std::mem::take(&mut state.events))
    }

    fn schedule(&self, record: &DriverRecord) -> liveupdate::Result<()> {
        let mut state = self.state.lock().unwrap();
        state.calls.push("schedule");
        state.scheduled.push(record.clone());
        if std::mem::take(&mut state.fail_next_schedule) {
            return Err(Error::new(ErrorCode::NativeError, "Scheduling failed."));
        }
        if std::mem::take(&mut state.refuse_next_schedule) {
            return Err(Error::new(
                ErrorCode::InvalidInput,
                "Invalid live update request.",
            ));
        }
        Ok(())
    }

    fn starts(&self) -> liveupdate::Result<Vec<StartReport>> {
        let mut state = self.state.lock().unwrap();
        state.calls.push("starts");
        if std::mem::take(&mut state.fail_next_starts) {
            return Err(Error::new(ErrorCode::NativeError, "Asking failed."));
        }
        Ok(state.starts.clone())
    }
}

impl FakeDriver {
    pub fn edit<T>(&self, action: impl FnOnce(&mut FakeState) -> T) -> T {
        action(&mut self.state.lock().unwrap())
    }

    pub fn records(&self) -> Vec<DriverRecord> {
        self.state.lock().unwrap().records.clone()
    }

    /// Posts and cancels so far.
    pub fn counts(&self) -> (usize, usize) {
        let state = self.state.lock().unwrap();
        (state.posts, state.cancels)
    }

    /// Takes the calls made so far.
    pub fn take_calls(&self) -> Vec<&'static str> {
        std::mem::take(&mut self.state.lock().unwrap().calls)
    }

    /// Removes a notification the way a user's swipe does.
    pub fn dismiss(&self, id: &str) {
        self.state.lock().unwrap().observed.remove(id);
    }
}

pub fn snapshot(revision: u64) -> Snapshot {
    Snapshot {
        revision,
        title: "Delivery".into(),
        status: "Preparing".into(),
        progress: Some(0.0),
        timer: None,
        stale_at: None,
        presentation: None,
    }
}

pub fn timer_snapshot(revision: u64) -> Snapshot {
    Snapshot {
        progress: None,
        timer: Some(Timer {
            started_at: 1_000,
            ends_at: 2_000,
        }),
        ..snapshot(revision)
    }
}

pub fn request(key: &str) -> StartRequest {
    StartRequest {
        start_at: None,
        placement: liveupdate::Placement::Tagged,
        activity_key: key.into(),
        schema_version: 1,
        template: Template::Progress,
        fallback: Fallback::Notification,
        visibility: Visibility::Private,
        snapshot: snapshot(0),
    }
}

pub fn assert_code<T: std::fmt::Debug>(result: liveupdate::Result<T>, code: ErrorCode) {
    assert_eq!(result.unwrap_err().code, code);
}
