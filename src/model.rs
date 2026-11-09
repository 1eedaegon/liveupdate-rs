use crate::AndroidPresentation;
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
    /// Resolved Android system presentation for schema version 2.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub presentation: Option<AndroidPresentation>,
}

impl Snapshot {
    /// Returns the system template implied by this snapshot.
    pub fn template(&self) -> Result<Template> {
        match (&self.progress, &self.timer, &self.presentation) {
            (Some(_), None, None) => Ok(Template::Progress),
            (None, Some(_), None) => Ok(Template::Timer),
            (None, None, Some(presentation)) => Ok(presentation.template()),
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
        let template = self.template()?;
        if let Some(presentation) = &self.presentation {
            return presentation.validate();
        }
        match template {
            Template::Progress => {
                let progress = self.progress.ok_or_else(Error::invalid)?;
                if !progress.is_finite() || !(0.0..=1.0).contains(&progress) {
                    return Err(Error::invalid());
                }
            }
            Template::Timer => {
                let timer = self.timer.as_ref().ok_or_else(Error::invalid)?;
                // `started_at` is then below MAX_TIMESTAMP too.
                if timer.ends_at > MAX_TIMESTAMP || timer.ends_at <= timer.started_at {
                    return Err(Error::invalid());
                }
            }
            Template::Standard | Template::BigText | Template::Metric => {
                return Err(Error::invalid());
            }
        }
        Ok(())
    }
}

/// Android system template used to present the live update.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Template {
    /// Standard notification.
    Standard,
    /// Expanded long-text notification.
    BigText,
    /// Progress notification.
    Progress,
    /// Countdown chronometer notification.
    Timer,
    /// API 37 metric notification.
    Metric,
}

/// Input for starting one logical live update.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StartRequest {
    /// Stable application key used for idempotent starts.
    pub activity_key: String,
    /// Contract version: `1` for legacy progress/timer, `2` for rich Android presentation.
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
        if !matches!(self.schema_version, 1 | 2)
            || !valid_key(&self.activity_key)
            || self.visibility == Visibility::Unknown
        {
            return Err(Error::invalid());
        }
        if (self.schema_version == 1) != self.snapshot.presentation.is_none() {
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

pub(crate) fn valid_text(value: &str, maximum: usize) -> bool {
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ErrorCode;
    use serde_json::json;

    fn progress(value: f64) -> Snapshot {
        Snapshot {
            revision: 0,
            title: "Delivery".into(),
            status: "Preparing".into(),
            progress: Some(value),
            timer: None,
            stale_at: None,
            presentation: None,
        }
    }

    fn timer(started_at: u64, ends_at: u64) -> Snapshot {
        Snapshot {
            progress: None,
            timer: Some(Timer {
                started_at,
                ends_at,
            }),
            ..progress(0.0)
        }
    }

    fn start(snapshot: Snapshot) -> StartRequest {
        StartRequest {
            activity_key: "delivery:1".into(),
            schema_version: 1,
            template: snapshot.template().unwrap_or(Template::Progress),
            fallback: Fallback::Notification,
            visibility: Visibility::Private,
            snapshot,
        }
    }

    fn valid(snapshot: &Snapshot) -> bool {
        snapshot.validate().is_ok()
    }

    // LU-VALID-1
    #[test]
    fn activity_keys_are_short_ascii_names() {
        for key in [
            "a",
            "A9",
            "delivery:order-123",
            "x.y_z:w-v",
            &"k".repeat(128),
        ] {
            assert!(valid_key(key), "{key:?}");
        }
        for key in [
            "", "-a", ".a", ":a", "_a", " a", "a b", "a/b", "é", "a\u{301}",
        ] {
            assert!(!valid_key(key), "{key:?}");
        }
        assert!(!valid_key(&"k".repeat(129)));
    }

    // LU-VALID-2, LU-VALID-3
    #[test]
    fn a_start_needs_schema_one_and_a_known_visibility() {
        assert!(start(progress(0.5)).validate().is_ok());
        for schema_version in [0, 2, u8::MAX] {
            let request = StartRequest {
                schema_version,
                ..start(progress(0.5))
            };
            assert_eq!(
                request.validate().unwrap_err().code,
                ErrorCode::InvalidInput
            );
        }
        for visibility in [Visibility::Public, Visibility::Private, Visibility::Secret] {
            assert!(
                StartRequest {
                    visibility,
                    ..start(progress(0.5))
                }
                .validate()
                .is_ok()
            );
        }
        let unknown = StartRequest {
            visibility: Visibility::Unknown,
            ..start(progress(0.5))
        };
        assert!(unknown.validate().is_err());
    }

    // LU-VALID-4
    #[test]
    fn titles_and_statuses_count_unicode_scalar_values() {
        assert!(valid_text(&"가".repeat(120), 120));
        assert!(!valid_text(&"가".repeat(121), 120));
        assert!(valid_text(&"🍡".repeat(240), 240));
        assert!(!valid_text(&"🍡".repeat(241), 240));
        let mut long_status = progress(0.5);
        long_status.status = "s".repeat(241);
        assert!(!valid(&long_status));
        let mut long_title = progress(0.5);
        long_title.title = "t".repeat(121);
        assert!(!valid(&long_title));
    }

    // LU-VALID-4
    #[test]
    fn text_made_only_of_contract_whitespace_is_empty() {
        let whitespace = [
            '\t', '\n', '\u{b}', '\u{c}', '\r', ' ', '\u{a0}', '\u{1680}', '\u{2000}', '\u{2007}',
            '\u{200a}', '\u{2028}', '\u{2029}', '\u{202f}', '\u{205f}', '\u{3000}', '\u{feff}',
        ];
        for character in whitespace {
            assert!(!valid_text(&character.to_string(), 120), "{character:?}");
            assert!(valid_text(&format!("{character}x"), 120), "{character:?}");
        }
        assert!(!valid_text("", 120));
        // Not contract whitespace: kept, although some platforms treat them as blank.
        for character in ['\u{1c}', '\u{85}', '\u{200b}', '\u{0}'] {
            assert!(valid_text(&character.to_string(), 120), "{character:?}");
        }
    }

    // LU-VALID-5
    #[test]
    fn a_snapshot_has_exactly_one_template_value() {
        assert_eq!(progress(0.5).template().unwrap(), Template::Progress);
        assert_eq!(timer(1, 2).template().unwrap(), Template::Timer);
        let both = Snapshot {
            progress: Some(0.5),
            ..timer(1, 2)
        };
        let neither = Snapshot {
            progress: None,
            ..progress(0.5)
        };
        for snapshot in [both, neither] {
            assert!(snapshot.template().is_err());
            assert!(!valid(&snapshot));
        }
        let mismatched = StartRequest {
            template: Template::Timer,
            ..start(progress(0.5))
        };
        assert!(mismatched.validate().is_err());
    }

    // LU-VALID-6
    #[test]
    fn progress_is_finite_from_zero_to_one() {
        for value in [0.0, -0.0, 0.5, 1.0, f64::MIN_POSITIVE] {
            assert!(valid(&progress(value)), "{value}");
        }
        for value in [
            -f64::MIN_POSITIVE,
            1.000_000_1,
            f64::NAN,
            f64::INFINITY,
            f64::NEG_INFINITY,
        ] {
            assert!(!valid(&progress(value)), "{value}");
        }
    }

    // LU-VALID-7
    #[test]
    fn a_timer_ends_after_it_starts_within_the_timestamp_range() {
        assert!(valid(&timer(0, 1)));
        assert!(valid(&timer(MAX_TIMESTAMP - 1, MAX_TIMESTAMP)));
        for (started_at, ends_at) in [
            (2, 2),
            (3, 2),
            (0, MAX_TIMESTAMP + 1),
            (MAX_TIMESTAMP + 1, u64::MAX),
        ] {
            assert!(
                !valid(&timer(started_at, ends_at)),
                "{started_at}..{ends_at}"
            );
        }
    }

    // LU-VALID-8
    #[test]
    fn revisions_and_timestamps_have_javascript_safe_maximums() {
        let mut snapshot = progress(0.5);
        snapshot.revision = MAX_SAFE_INTEGER;
        snapshot.stale_at = Some(MAX_TIMESTAMP);
        assert!(valid(&snapshot));
        snapshot.revision = MAX_SAFE_INTEGER + 1;
        assert!(!valid(&snapshot));
        snapshot.revision = 0;
        snapshot.stale_at = Some(MAX_TIMESTAMP + 1);
        assert!(!valid(&snapshot));

        assert!(EndPolicy::Immediate.validate().is_ok());
        assert!(EndPolicy::Default.validate().is_ok());
        assert!(EndPolicy::After { at: MAX_TIMESTAMP }.validate().is_ok());
        assert!(
            EndPolicy::After {
                at: MAX_TIMESTAMP + 1
            }
            .validate()
            .is_err()
        );
    }

    // LU-VALID-9
    #[test]
    fn request_json_rejects_unknown_fields() {
        let snapshot = json!({ "revision": 0, "title": "T", "status": "S", "progress": 0.5 });
        assert!(serde_json::from_value::<Snapshot>(snapshot.clone()).is_ok());
        let mut extra = snapshot.clone();
        extra["color"] = json!("red");
        assert!(serde_json::from_value::<Snapshot>(extra).is_err());
        assert!(
            serde_json::from_value::<Timer>(json!({ "startedAt": 1, "endsAt": 2, "x": 0 }))
                .is_err()
        );
        let request = json!({
            "activityKey": "delivery:1",
            "schemaVersion": 1,
            "template": "progress",
            "fallback": "notification",
            "visibility": "private",
            "snapshot": snapshot,
        });
        assert!(serde_json::from_value::<StartRequest>(request.clone()).is_ok());
        let mut extra = request;
        extra["priority"] = json!(1);
        assert!(serde_json::from_value::<StartRequest>(extra).is_err());
        assert!(
            serde_json::from_value::<EndPolicy>(json!({ "kind": "after", "at": 1, "x": 0 }))
                .is_err()
        );
    }

    // LU-VALID-9
    #[test]
    fn end_policies_and_states_use_the_stable_json_names() {
        for (policy, expected) in [
            (EndPolicy::Immediate, json!({ "kind": "immediate" })),
            (EndPolicy::Default, json!({ "kind": "default" })),
            (
                EndPolicy::After { at: 5 },
                json!({ "kind": "after", "at": 5 }),
            ),
        ] {
            assert_eq!(serde_json::to_value(policy).unwrap(), expected);
            assert_eq!(
                serde_json::from_value::<EndPolicy>(expected).unwrap(),
                policy
            );
        }
        for (state, name) in [
            (ActivityState::Unknown, "unknown"),
            (ActivityState::Active, "active"),
            (ActivityState::Stale, "stale"),
            (ActivityState::Ended, "ended"),
            (ActivityState::Dismissed, "dismissed"),
        ] {
            assert_eq!(serde_json::to_value(state).unwrap(), json!(name));
        }
        assert_eq!(
            serde_json::to_value(Template::Timer).unwrap(),
            json!("timer")
        );
    }

    // LU-VALID-10
    #[test]
    fn activity_ids_are_non_blank_and_at_most_256_characters() {
        assert!(valid_id(&"i".repeat(256)));
        assert!(valid_id(&"가".repeat(256)));
        assert!(!valid_id(&"i".repeat(257)));
        assert!(!valid_id(""));
        assert!(!valid_id(" \u{3000}"));
    }
}
