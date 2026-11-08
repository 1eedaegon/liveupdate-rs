use crate::{
    ActivityState, Capabilities, PromotionState, Result, Snapshot, StartRequest, Visibility,
    model::Backend,
};
use serde::{Deserialize, Serialize};

/// Durable internal receipt exchanged with an Android driver.
///
/// Driver implementations persist this value verbatim but must not treat it as
/// authorization to operate on an activity that Rust did not load from storage.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DriverRecord {
    /// Opaque activity handle.
    pub id: String,
    /// Original request used for idempotency and template policy.
    pub origin: StartRequest,
    /// Latest accepted snapshot.
    pub snapshot: Snapshot,
    /// Last reconciled state.
    pub state: ActivityState,
    /// Whether promoted presentation was requested.
    pub promotion_requested: bool,
    /// Promotion state reported by Android.
    pub promotion_state: PromotionState,
    /// Whether Android has not yet confirmed the latest post.
    pub awaiting_observation: bool,
    /// Whether the latest post may need an idempotent retry.
    pub post_failed: bool,
}

impl DriverRecord {
    pub(crate) fn terminal(&self) -> bool {
        matches!(self.state, ActivityState::Ended | ActivityState::Dismissed)
    }

    pub(crate) fn public(&self) -> crate::ActivityRecord {
        let degraded =
            !self.promotion_requested || self.promotion_state == PromotionState::NotPromoted;
        crate::ActivityRecord {
            id: self.id.clone(),
            activity_key: self.origin.activity_key.clone(),
            schema_version: self.origin.schema_version,
            template: self.origin.template,
            snapshot: self.snapshot.clone(),
            backend: Backend::AndroidNotification,
            state: self.state,
            visibility: Visibility::Unknown,
            degraded_features: if degraded {
                vec!["nativeLiveActivity".into()]
            } else {
                Vec::new()
            },
            promotion_requested: self.promotion_requested,
            promotion_state: self.promotion_state,
        }
    }
}

/// State read from an Android notification currently owned by the driver.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Observation {
    /// Opaque activity handle embedded in the notification.
    pub id: String,
    /// Snapshot embedded in the notification.
    pub snapshot: Snapshot,
    /// Observed active or stale state.
    pub state: ActivityState,
    /// Whether the notification requested promotion.
    pub promotion_requested: bool,
    /// Promotion state derived from Android notification flags.
    pub promotion_state: PromotionState,
}

/// Android data-layer boundary used by the Rust lifecycle.
///
/// Implementations own framework calls and persistence only. Validation,
/// idempotency, ordering, limits and lifecycle decisions remain in Rust.
pub trait Driver: Send + Sync {
    /// Reads current Android notification and promotion capabilities.
    fn capabilities(&self) -> Result<Capabilities>;
    /// Reads durable Rust lifecycle receipts.
    fn read(&self) -> Result<Vec<DriverRecord>>;
    /// Atomically replaces durable Rust lifecycle receipts.
    fn write(&self, records: &[DriverRecord]) -> Result<()>;
    /// Reports currently active notifications owned by this driver.
    fn observe(&self) -> Result<Vec<Observation>>;
    /// Posts or replaces the notification represented by a receipt.
    fn post(&self, record: &DriverRecord) -> Result<()>;
    /// Cancels the notification for an activity handle.
    fn cancel(&self, id: &str) -> Result<()>;
}
