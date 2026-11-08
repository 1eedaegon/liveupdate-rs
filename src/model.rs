use crate::{
    Error, Result,
    contract::{Fallback, PromotionState, Visibility},
};
use serde::{Deserialize, Serialize};

/// Maximum integer that round-trips exactly through a JavaScript number.
pub const MAX_SAFE_INTEGER: u64 = 9_007_199_254_740_991;
/// Maximum timestamp accepted by JavaScript `Date`.
pub const MAX_TIMESTAMP: u64 = 8_640_000_000_000_000;

/// Countdown interval expressed as Unix timestamps in milliseconds.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Timer {
    /// Start timestamp in milliseconds.
    pub started_at: u64,
    /// End timestamp in milliseconds, strictly after `started_at`.
    pub ends_at: u64,
}

/// Content shown by a progress or timer live update.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Snapshot {
    /// Monotonically increasing application revision.
    pub revision: u64,
    /// Notification title, limited to 120 Unicode scalar values.
    pub title: String,
    /// Notification status text, limited to 240 Unicode scalar values.
    pub status: String,
    /// Progress from zero through one for the progress template.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub progress: Option<f64>,
    /// Countdown interval for the timer template.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timer: Option<Timer>,
    /// Optional time at which the content becomes stale.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stale_at: Option<u64>,
}

impl Snapshot {
    /// Returns the system template implied by this snapshot.
    pub fn template(&self) -> Result<Template> {
        match (&self.progress, &self.timer) {
            (Some(_), None) => Ok(Template::Progress),
            (None, Some(_)) => Ok(Template::Timer),
            _ => Err(Error::invalid()),
        }
    }

    /// Validates all snapshot limits and template-specific values.
    pub fn validate(&self) -> Result<()> {
        if self.revision > MAX_SAFE_INTEGER
            || !valid_text(&self.title, 120)
            || !valid_text(&self.status, 240)
            || self.stale_at.is_some_and(|value| value > MAX_TIMESTAMP)
        {
            return Err(Error::invalid());
        }
        match self.template()? {
            Template::Progress => {
                let progress = self.progress.ok_or_else(Error::invalid)?;
                if !progress.is_finite() || !(0.0..=1.0).contains(&progress) {
                    return Err(Error::invalid());
                }
            }
            Template::Timer => {
                let timer = self.timer.as_ref().ok_or_else(Error::invalid)?;
                if timer.started_at > MAX_TIMESTAMP
                    || timer.ends_at > MAX_TIMESTAMP
                    || timer.ends_at <= timer.started_at
                {
                    return Err(Error::invalid());
                }
            }
        }
        Ok(())
    }
}

/// Android system template used to present the live update.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Template {
    /// Progress notification.
    Progress,
    /// Countdown chronometer notification.
    Timer,
}

/// Input for starting one logical live update.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StartRequest {
    /// Stable application key used for idempotent starts.
    pub activity_key: String,
    /// Contract version; currently exactly `1`.
    pub schema_version: u8,
    /// System template that must match the snapshot.
    pub template: Template,
    /// Behavior when promoted presentation is unavailable.
    pub fallback: Fallback,
    /// Lock-screen content policy requested from Android.
    pub visibility: Visibility,
    /// Initial content at its first revision.
    pub snapshot: Snapshot,
}

impl StartRequest {
    /// Validates the complete start request.
    pub fn validate(&self) -> Result<()> {
        if self.schema_version != 1
            || !valid_key(&self.activity_key)
            || self.visibility == Visibility::Unknown
        {
            return Err(Error::invalid());
        }
        self.snapshot.validate()?;
        if self.snapshot.template()? != self.template {
            return Err(Error::invalid());
        }
        Ok(())
    }
}

/// Android dismissal policy for ending an activity.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase", deny_unknown_fields)]
pub enum EndPolicy {
    /// Remove the notification immediately.
    Immediate,
    /// Use the platform default, which is immediate on Android.
    Default,
    /// Request delayed dismissal at a timestamp; unsupported on Android.
    After {
        /// Requested dismissal timestamp in milliseconds.
        at: u64,
    },
}

impl EndPolicy {
    pub(crate) fn validate(self) -> Result<()> {
        if matches!(self, Self::After { at } if at > MAX_TIMESTAMP) {
            return Err(Error::invalid());
        }
        Ok(())
    }
}

/// Lifecycle state observed for a live update.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
#[non_exhaustive]
pub enum ActivityState {
    /// The request is accepted but not yet observed in Android.
    Unknown,
    /// Android currently shows the notification.
    Active,
    /// Android shows content whose stale timestamp elapsed.
    Stale,
    /// The application explicitly ended the activity.
    Ended,
    /// The user or system removed an observed notification.
    Dismissed,
}

/// Native presentation backend used by an activity record.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Backend {
    /// Android notification and promoted ongoing presentation.
    AndroidNotification,
}

/// Public receipt returned from start, update, end and list operations.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivityRecord {
    /// Opaque activity handle generated by the Rust lifecycle.
    pub id: String,
    /// Stable application key from the original start request.
    pub activity_key: String,
    /// Contract version used by this activity.
    pub schema_version: u8,
    /// System template fixed at start.
    pub template: Template,
    /// Latest accepted content.
    pub snapshot: Snapshot,
    /// Android presentation backend.
    pub backend: Backend,
    /// Last reconciled lifecycle state.
    pub state: ActivityState,
    /// Visibility cannot be proven from notification acceptance alone.
    pub visibility: Visibility,
    /// Features that degraded to fallback presentation.
    pub degraded_features: Vec<String>,
    /// Whether Rust asked Android for promoted ongoing presentation.
    pub promotion_requested: bool,
    /// Promotion state observed from Android.
    pub promotion_state: PromotionState,
}

pub(crate) fn valid_id(value: &str) -> bool {
    valid_text(value, 256)
}

fn valid_key(value: &str) -> bool {
    let mut characters = value.chars();
    let Some(first) = characters.next() else {
        return false;
    };
    value.chars().count() <= 128
        && first.is_ascii_alphanumeric()
        && characters.all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | ':' | '-')
        })
}

fn valid_text(value: &str, maximum: usize) -> bool {
    !value.is_empty()
        && value.chars().count() <= maximum
        && !value.chars().all(is_contract_whitespace)
}

fn is_contract_whitespace(character: char) -> bool {
    matches!(
        character as u32,
        0x0009..=0x000D
            | 0x0020
            | 0x00A0
            | 0x1680
            | 0x2000..=0x200A
            | 0x2028
            | 0x2029
            | 0x202F
            | 0x205F
            | 0x3000
            | 0xFEFF
    )
}
