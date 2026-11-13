use crate::{
    ActivityRecord, ActivityState, Capabilities, Driver, DriverRecord, EndPolicy, Error, ErrorCode,
    EventBatch, Fallback, Observation, Placement, PromotionState, Result, Snapshot, StartRequest,
    model::valid_id,
};
use std::{
    collections::{BTreeMap, BTreeSet},
    sync::{Mutex, PoisonError},
};
use uuid::Uuid;

const ACTIVE_LIMIT: usize = 32;
const TOMBSTONE_LIMIT: usize = 128;
/// Android can still list notifications of terminal records, whose cancellation is asynchronous,
/// so observations are bounded by every record that can be kept, as the adapter bounds them.
const OBSERVATION_LIMIT: usize = ACTIVE_LIMIT + TOMBSTONE_LIMIT;

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

    /// Ends an activity. Its notification is removed at once, or with `EndPolicy::After` keeps the
    /// last content, no longer ongoing, until that time. Ending it again without a time removes
    /// that last content early.
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

    /// Atomically drains Android notification action and dismissal events.
    pub fn events(&self) -> Result<EventBatch> {
        self.serialized(|driver| {
            let events = driver.drain_events()?;
            events.validate()?;
            Ok(events)
        })
    }

    fn serialized<T>(&self, action: impl FnOnce(&D) -> Result<T>) -> Result<T> {
        // The lock guards no data: a driver that panicked left only durable receipts, which every
        // operation reconciles, so a poisoned lock is taken over instead of failing forever.
        let _guard = self
            .operations
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
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
            retry(driver, existing.clone(), &records)
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
    ensure_placement(request.placement, capabilities, &records)?;
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
        dismiss_at: None,
    };
    let next: Vec<_> = records.iter().cloned().chain([record.clone()]).collect();
    deliver(driver, record, &next, &records)
}

fn update(driver: &impl Driver, id: &str, snapshot: Snapshot) -> Result<ActivityRecord> {
    if !valid_id(id) {
        return Err(Error::invalid());
    }
    snapshot.validate()?;
    let records = reconcile(driver)?;
    let previous = find_record(&records, id)?;
    if previous.terminal() {
        return Err(not_found());
    }
    if !replaces(&previous, &snapshot)? {
        return if previous.post_failed {
            retry(driver, previous, &records)
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
    deliver(driver, record, &next, &records)
}

fn end(driver: &impl Driver, id: &str, policy: EndPolicy) -> Result<ActivityRecord> {
    if !valid_id(id) {
        return Err(Error::invalid());
    }
    policy.validate()?;
    let records = reconcile(driver)?;
    let previous = find_record(&records, id)?;
    let dismiss_at = match policy {
        EndPolicy::After { at } => Some(at),
        EndPolicy::Immediate | EndPolicy::Default => None,
    };
    if previous.state == ActivityState::Ended {
        return remove_last_content(driver, &records, previous, dismiss_at);
    }
    if dismiss_at.is_some() && !driver.capabilities()?.presentation.delayed_end {
        return Err(failure(
            ErrorCode::UnsupportedFeature,
            "Delayed activity dismissal is unavailable.",
        ));
    }
    // A notification the user removed is not posted again.
    let dismiss_at = dismiss_at.filter(|_| previous.state != ActivityState::Dismissed);
    let ended = DriverRecord {
        state: ActivityState::Ended,
        awaiting_observation: false,
        post_failed: false,
        dismiss_at,
        ..previous
    };
    save(driver, &retire(&records, &ended))?;
    if dismiss_at.is_some() {
        // The adapter posts the last content in its final form, which it no longer observes.
        driver.post(&ended)?;
    } else {
        driver.cancel(id)?;
    }
    Ok(ended.public())
}

/// The end of an ended activity. It changes nothing, except that an end without a time removes the
/// last content that a delayed end left on screen.
fn remove_last_content(
    driver: &impl Driver,
    records: &[DriverRecord],
    ended: DriverRecord,
    dismiss_at: Option<u64>,
) -> Result<ActivityRecord> {
    if ended.dismiss_at.is_none() || dismiss_at.is_some() {
        return Ok(ended.public());
    }
    let removed = DriverRecord {
        dismiss_at: None,
        ..ended
    };
    save(driver, &replace(records, &removed))?;
    driver.cancel(&removed.id)?;
    Ok(removed.public())
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
        // The snapshot and the promotion request match the receipt, as checked above.
        return Ok(DriverRecord {
            state: native.state,
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
    if !promotion_matches || !kept(&previous.origin, &native.snapshot) {
        return Err(Error::native());
    }
    Ok(())
}

/// Posts the pending receipt of `record` again, for the same start or the same update.
///
/// When the adapter now refuses it as invalid, an activity that was updated keeps its receipt: the
/// content before the refused update is not known. One that never was has only this content, so it
/// goes, after the notification that an earlier post may have left without its answer.
fn retry(
    driver: &impl Driver,
    record: DriverRecord,
    records: &[DriverRecord],
) -> Result<ActivityRecord> {
    let refusal = match deliver(driver, record.clone(), records, records) {
        Err(failure) if failure.code == ErrorCode::InvalidInput => failure,
        other => return other,
    };
    if record.snapshot != record.origin.snapshot {
        return Err(refusal);
    }
    driver.cancel(&record.id)?;
    let without: Vec<_> = records
        .iter()
        .filter(|other| other.id != record.id)
        .cloned()
        .collect();
    save(driver, &without)?;
    Err(refusal)
}

/// Stores `record` among `records` as a pending receipt, posts it and confirms it.
///
/// `before` is what was stored when the operation began. The adapter refuses a post as invalid
/// before it posts anything, and would refuse it again: that content must hold neither the key nor
/// the revision, so the receipts go back to `before`. Any other failure leaves the pending receipt
/// for the same operation to post again. So does a failure to store `before`, whose error is then
/// the answer: the same operation meets the refusal again and restores again.
fn deliver(
    driver: &impl Driver,
    record: DriverRecord,
    records: &[DriverRecord],
    before: &[DriverRecord],
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
    let pending_records = replace(records, &pending);
    save(driver, &pending_records)?;
    if let Err(failure) = driver.post(&pending) {
        if failure.code == ErrorCode::InvalidInput {
            save(driver, before)?;
        }
        return Err(failure);
    }
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
            || !kept(&record.origin, &record.snapshot)
        {
            return Err(Error::native());
        }
    }
    Ok(records)
}

fn checked_observations(observations: Vec<Observation>) -> Result<BTreeMap<String, Observation>> {
    if observations.len() > OBSERVATION_LIMIT {
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

/// Whether a stored or observed `snapshot` can be of the activity that `origin` started: schema
/// version 1 keeps its template, schema version 2 has any. This is all an earlier release checked,
/// so a receipt it wrote for an update that mixed the generations, and whose post the adapter then
/// refused, is still read; the next update that fits replaces it.
fn kept(origin: &StartRequest, snapshot: &Snapshot) -> bool {
    match snapshot.template() {
        Ok(template) => origin.schema_version != 1 || template == origin.template,
        Err(_) => false,
    }
}

/// Whether a new `snapshot` can be content of the activity that `origin` started. Schema version 1
/// keeps its template; schema version 2 keeps its generation and may change its system style. A
/// public version needs the private activity.
fn fits(origin: &StartRequest, snapshot: &Snapshot) -> bool {
    let shape = if origin.schema_version == 1 {
        snapshot.presentation.is_none() && snapshot.template().ok() == Some(origin.template)
    } else {
        snapshot.presentation.is_some()
    };
    shape && snapshot.public_version_fits(origin.visibility)
}

fn replaces(previous: &DriverRecord, snapshot: &Snapshot) -> Result<bool> {
    if !fits(&previous.origin, snapshot) {
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

/// Replaces a record that just became terminal and moves it last, so it is the newest tombstone
/// and the oldest one is evicted first.
fn retire(records: &[DriverRecord], terminal: &DriverRecord) -> Vec<DriverRecord> {
    records
        .iter()
        .filter(|record| record.id != terminal.id)
        .cloned()
        .chain([terminal.clone()])
        .collect()
}

fn find_record(records: &[DriverRecord], id: &str) -> Result<DriverRecord> {
    records
        .iter()
        .find(|record| record.id == id)
        .cloned()
        .ok_or_else(not_found)
}

/// A foreground placement needs an adapter that knows it, and is the only live one.
fn ensure_placement(
    placement: Placement,
    capabilities: Capabilities,
    records: &[DriverRecord],
) -> Result<()> {
    if placement != Placement::Foreground {
        return Ok(());
    }
    if !capabilities.presentation.foreground_placement {
        return Err(failure(
            ErrorCode::UnsupportedFeature,
            "Foreground placement is unavailable.",
        ));
    }
    let taken = records
        .iter()
        .any(|record| !record.terminal() && record.origin.placement == Placement::Foreground);
    if taken {
        return Err(failure(
            ErrorCode::SystemLimit,
            "Another activity has the foreground placement.",
        ));
    }
    Ok(())
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

#[cfg(test)]
mod tests;
