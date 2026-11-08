use crate::{
    ActivityRecord, ActivityState, Capabilities, Driver, DriverRecord, EndPolicy, Error, ErrorCode,
    Fallback, Observation, PromotionState, Result, Snapshot, StartRequest, model::valid_id,
};
use std::{
    collections::{BTreeMap, BTreeSet},
    sync::Mutex,
};
use uuid::Uuid;

const ACTIVE_LIMIT: usize = 32;
const TOMBSTONE_LIMIT: usize = 128;

/// Thread-safe Rust owner of Android live update lifecycle and ordering.
pub struct LiveUpdate<D> {
    driver: D,
    operations: Mutex<()>,
}

impl<D: Driver> LiveUpdate<D> {
    /// Creates a client around an Android framework and persistence driver.
    #[must_use]
    pub fn new(driver: D) -> Self {
        Self {
            driver,
            operations: Mutex::new(()),
        }
    }

    /// Returns the capabilities currently reported by Android.
    pub fn capabilities(&self) -> Result<Capabilities> {
        self.serialized(|driver| driver.capabilities())
    }

    /// Starts an idempotent live update for an application key.
    pub fn start(&self, request: StartRequest) -> Result<ActivityRecord> {
        self.serialized(|driver| start(driver, request))
    }

    /// Replaces content when `snapshot.revision` is newer.
    pub fn update(&self, id: &str, snapshot: Snapshot) -> Result<ActivityRecord> {
        self.serialized(|driver| update(driver, id, snapshot))
    }

    /// Ends an activity and removes its Android notification.
    pub fn end(&self, id: &str, policy: EndPolicy) -> Result<ActivityRecord> {
        self.serialized(|driver| end(driver, id, policy))
    }

    /// Reconciles durable receipts with Android and returns every retained record.
    pub fn list(&self) -> Result<Vec<ActivityRecord>> {
        self.serialized(|driver| {
            Ok(reconcile(driver)?
                .iter()
                .map(DriverRecord::public)
                .collect())
        })
    }

    fn serialized<T>(&self, action: impl FnOnce(&D) -> Result<T>) -> Result<T> {
        let _guard = self.operations.lock().map_err(|_| Error::native())?;
        action(&self.driver)
    }
}

fn start(driver: &impl Driver, request: StartRequest) -> Result<ActivityRecord> {
    request.validate()?;
    let capabilities = driver.capabilities()?;
    ensure_permission(capabilities)?;
    let records = reconcile(driver)?;
    if let Some(existing) = records
        .iter()
        .find(|record| !record.terminal() && record.origin.activity_key == request.activity_key)
    {
        if existing.origin != request {
            return Err(failure(
                ErrorCode::Conflict,
                "An activity with this key already exists.",
            ));
        }
        return if existing.post_failed {
            deliver(driver, existing.clone(), records)
        } else {
            Ok(existing.public())
        };
    }
    let promote = capabilities.can_promote();
    if !promote && request.fallback == Fallback::None {
        return Err(failure(
            ErrorCode::UnsupportedFeature,
            "Live Update promotion is unavailable and fallback was not allowed.",
        ));
    }
    if records.iter().filter(|record| !record.terminal()).count() >= ACTIVE_LIMIT {
        return Err(failure(
            ErrorCode::SystemLimit,
            "Too many active activities.",
        ));
    }
    let record = DriverRecord {
        id: Uuid::new_v4().to_string(),
        snapshot: request.snapshot.clone(),
        origin: request,
        state: ActivityState::Unknown,
        promotion_requested: promote,
        promotion_state: if promote {
            PromotionState::Unknown
        } else {
            PromotionState::NotApplicable
        },
        awaiting_observation: true,
        post_failed: false,
    };
    let next = records.iter().cloned().chain([record.clone()]).collect();
    deliver(driver, record, next)
}

fn update(driver: &impl Driver, id: &str, snapshot: Snapshot) -> Result<ActivityRecord> {
    if !valid_id(id) {
        return Err(Error::invalid());
    }
    snapshot.validate()?;
    let records = reconcile(driver)?;
    let previous = find_record(&records, id)?;
    if previous.terminal()
        || (previous.state == ActivityState::Unknown && !previous.awaiting_observation)
    {
        return Err(not_found());
    }
    if !replaces(&previous, &snapshot)? {
        return if previous.post_failed {
            deliver(driver, previous, records)
        } else {
            Ok(previous.public())
        };
    }
    ensure_permission(driver.capabilities()?)?;
    let record = DriverRecord {
        snapshot,
        state: ActivityState::Unknown,
        awaiting_observation: true,
        ..previous
    };
    let next = replace(&records, &record);
    deliver(driver, record, next)
}

fn end(driver: &impl Driver, id: &str, policy: EndPolicy) -> Result<ActivityRecord> {
    if !valid_id(id) {
        return Err(Error::invalid());
    }
    policy.validate()?;
    let records = reconcile(driver)?;
    let previous = find_record(&records, id)?;
    if matches!(policy, EndPolicy::After { .. }) {
        return Err(failure(
            ErrorCode::UnsupportedFeature,
            "Android does not support delayed activity dismissal.",
        ));
    }
    if previous.state == ActivityState::Ended {
        return Ok(previous.public());
    }
    let ended = DriverRecord {
        state: ActivityState::Ended,
        awaiting_observation: false,
        post_failed: false,
        ..previous
    };
    save(driver, &replace(&records, &ended))?;
    driver.cancel(id)?;
    Ok(ended.public())
}

fn reconcile(driver: &impl Driver) -> Result<Vec<DriverRecord>> {
    let records = checked_records(driver.read()?)?;
    settle(driver, records, false)
}

fn settle(
    driver: &impl Driver,
    records: Vec<DriverRecord>,
    always: bool,
) -> Result<Vec<DriverRecord>> {
    let observations = checked_observations(driver.observe()?)?;
    for record in records
        .iter()
        .filter(|record| record.terminal() && observations.contains_key(record.id.as_str()))
    {
        driver.cancel(&record.id)?;
    }
    let reconciled: Vec<_> = records
        .iter()
        .map(|record| reconcile_record(record, observations.get(record.id.as_str())))
        .collect::<Result<_>>()?;
    if always || reconciled != records {
        let previous_terminal: BTreeSet<_> = records
            .iter()
            .filter(|record| record.terminal())
            .map(|record| record.id.as_str())
            .collect();
        let mut ordered = reconciled;
        ordered.sort_by_key(|record| {
            usize::from(record.terminal() && !previous_terminal.contains(record.id.as_str()))
        });
        return save(driver, &ordered);
    }
    Ok(reconciled)
}

fn reconcile_record(previous: &DriverRecord, native: Option<&Observation>) -> Result<DriverRecord> {
    if previous.terminal() {
        return Ok(previous.clone());
    }
    if let Some(native) = native {
        validate_observation(previous, native)?;
        if previous.awaiting_observation && native.snapshot.revision < previous.snapshot.revision {
            return Ok(previous.clone());
        }
        if native.snapshot != previous.snapshot {
            return Err(Error::native());
        }
        return Ok(DriverRecord {
            snapshot: native.snapshot.clone(),
            state: native.state,
            promotion_requested: native.promotion_requested,
            promotion_state: native.promotion_state,
            awaiting_observation: false,
            post_failed: false,
            ..previous.clone()
        });
    }
    let state = if matches!(previous.state, ActivityState::Active | ActivityState::Stale) {
        ActivityState::Dismissed
    } else {
        ActivityState::Unknown
    };
    Ok(DriverRecord {
        state,
        ..previous.clone()
    })
}

fn validate_observation(previous: &DriverRecord, native: &Observation) -> Result<()> {
    let state_matches_request = if native.promotion_requested {
        native.promotion_state != PromotionState::NotApplicable
    } else {
        native.promotion_state == PromotionState::NotApplicable
    };
    let promotion_matches =
        native.promotion_requested == previous.promotion_requested && state_matches_request;
    if !promotion_matches || native.snapshot.template()? != previous.origin.template {
        return Err(Error::native());
    }
    Ok(())
}

fn deliver(
    driver: &impl Driver,
    record: DriverRecord,
    records: Vec<DriverRecord>,
) -> Result<ActivityRecord> {
    let pending = DriverRecord {
        promotion_state: if record.promotion_requested {
            PromotionState::Unknown
        } else {
            PromotionState::NotApplicable
        },
        post_failed: true,
        ..record
    };
    let pending_records = replace(&records, &pending);
    save(driver, &pending_records)?;
    driver.post(&pending)?;
    let confirmed = DriverRecord {
        post_failed: false,
        ..pending
    };
    let confirmed_records = replace(&pending_records, &confirmed);
    settle(driver, confirmed_records, true)?;
    let persisted = checked_records(driver.read()?)?;
    find_record(&persisted, &confirmed.id).map(|record| record.public())
}

fn checked_records(records: Vec<DriverRecord>) -> Result<Vec<DriverRecord>> {
    let active = records.iter().filter(|record| !record.terminal()).count();
    let terminal = records.len() - active;
    if active > ACTIVE_LIMIT || terminal > TOMBSTONE_LIMIT {
        return Err(Error::native());
    }
    let mut ids = BTreeSet::new();
    for record in &records {
        if !valid_id(&record.id)
            || !ids.insert(record.id.as_str())
            || record.origin.validate().is_err()
            || record.snapshot.validate().is_err()
            || record.snapshot.template().ok() != Some(record.origin.template)
        {
            return Err(Error::native());
        }
    }
    Ok(records)
}

fn checked_observations(observations: Vec<Observation>) -> Result<BTreeMap<String, Observation>> {
    if observations.len() > ACTIVE_LIMIT {
        return Err(Error::native());
    }
    let mut checked = BTreeMap::new();
    for observation in observations {
        if !valid_id(&observation.id)
            || observation.snapshot.validate().is_err()
            || !matches!(
                observation.state,
                ActivityState::Active | ActivityState::Stale
            )
            || checked
                .insert(observation.id.clone(), observation)
                .is_some()
        {
            return Err(Error::native());
        }
    }
    Ok(checked)
}

fn replaces(previous: &DriverRecord, snapshot: &Snapshot) -> Result<bool> {
    if snapshot.template()? != previous.origin.template {
        return Err(Error::invalid());
    }
    if snapshot.revision < previous.snapshot.revision {
        return Err(failure(
            ErrorCode::StaleRevision,
            "Activity revision is stale.",
        ));
    }
    if snapshot.revision > previous.snapshot.revision {
        return Ok(true);
    }
    if snapshot != &previous.snapshot {
        return Err(failure(
            ErrorCode::Conflict,
            "Revision already contains different content.",
        ));
    }
    Ok(false)
}

fn save(driver: &impl Driver, records: &[DriverRecord]) -> Result<Vec<DriverRecord>> {
    let active = records.iter().filter(|record| !record.terminal()).cloned();
    let terminal: Vec<_> = records
        .iter()
        .filter(|record| record.terminal())
        .rev()
        .take(TOMBSTONE_LIMIT)
        .cloned()
        .collect();
    let kept: Vec<_> = active.chain(terminal.into_iter().rev()).collect();
    driver.write(&kept)?;
    Ok(kept)
}

fn replace(records: &[DriverRecord], replacement: &DriverRecord) -> Vec<DriverRecord> {
    records
        .iter()
        .map(|record| {
            if record.id == replacement.id {
                replacement.clone()
            } else {
                record.clone()
            }
        })
        .collect()
}

fn find_record(records: &[DriverRecord], id: &str) -> Result<DriverRecord> {
    records
        .iter()
        .find(|record| record.id == id)
        .cloned()
        .ok_or_else(not_found)
}

fn ensure_permission(capabilities: Capabilities) -> Result<()> {
    if capabilities.can_post() {
        Ok(())
    } else {
        Err(failure(
            ErrorCode::PermissionDenied,
            "Notifications are disabled for this app or channel.",
        ))
    }
}

fn not_found() -> Error {
    failure(ErrorCode::NotFound, "Unknown activity.")
}

fn failure(code: ErrorCode, message: &'static str) -> Error {
    Error::new(code, message)
}
