use crate::{
    ActivityState, Capabilities, EventBatch, PromotionState, Result, Snapshot, StartRequest,
    Visibility,
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
    /// Atomically returns and removes queued Android interaction events.
    ///
    /// The default keeps existing custom drivers source-compatible.
    fn drain_events(&self) -> Result<EventBatch> {
        Ok(EventBatch::default())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Fallback, Template};

    fn record(promotion_requested: bool, promotion_state: PromotionState) -> DriverRecord {
        let snapshot = Snapshot {
            revision: 3,
            title: "Delivery".into(),
            status: "Preparing".into(),
            progress: Some(0.5),
            timer: None,
            stale_at: None,
            presentation: None,
        };
        DriverRecord {
            id: "id".into(),
            origin: StartRequest {
                activity_key: "delivery:1".into(),
                schema_version: 1,
                template: Template::Progress,
                fallback: Fallback::Notification,
                visibility: Visibility::Secret,
                snapshot: Snapshot {
                    revision: 0,
                    ..snapshot.clone()
                },
            },
            snapshot,
            state: ActivityState::Active,
            promotion_requested,
            promotion_state,
            awaiting_observation: false,
            post_failed: false,
        }
    }

    // LU-CAP-4
    #[test]
    fn a_record_is_degraded_unless_promotion_was_requested_and_not_refused() {
        for (requested, state, degraded) in [
            (false, PromotionState::NotApplicable, true),
            (true, PromotionState::NotPromoted, true),
            (true, PromotionState::Promoted, false),
            (true, PromotionState::Unknown, false),
        ] {
            let public = record(requested, state).public();
            assert_eq!(
                !public.degraded_features.is_empty(),
                degraded,
                "{requested} {state:?}"
            );
            if degraded {
                assert_eq!(public.degraded_features, ["nativeLiveActivity"]);
            }
            assert_eq!(public.promotion_requested, requested);
            assert_eq!(public.promotion_state, state);
        }
    }

    // LU-START-1
    #[test]
    fn the_public_record_keeps_the_origin_and_hides_the_visibility() {
        let record = record(false, PromotionState::NotApplicable);
        let public = record.public();
        assert_eq!(public.id, "id");
        assert_eq!(public.activity_key, "delivery:1");
        assert_eq!(public.schema_version, 1);
        assert_eq!(public.template, Template::Progress);
        assert_eq!(public.snapshot.revision, 3);
        assert!(
            serde_json::to_value(&public)
                .unwrap()
                .get("backend")
                .is_none()
        );
        assert_eq!(public.visibility, Visibility::Unknown);
    }

    // LU-END-1, LU-LIST-3
    #[test]
    fn ended_and_dismissed_records_are_terminal() {
        for (state, terminal) in [
            (ActivityState::Unknown, false),
            (ActivityState::Active, false),
            (ActivityState::Stale, false),
            (ActivityState::Ended, true),
            (ActivityState::Dismissed, true),
        ] {
            let record = DriverRecord {
                state,
                ..record(false, PromotionState::NotApplicable)
            };
            assert_eq!(record.terminal(), terminal, "{state:?}");
        }
    }
}
