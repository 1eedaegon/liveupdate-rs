use serde::{Deserialize, Serialize};

/// Notification and promoted-ongoing support observed on the device.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Capabilities {
    /// Whether the Android version can present the requested Live Update shape.
    pub supported: bool,
    /// Whether the application may currently post notifications.
    pub notifications_enabled: bool,
    /// Whether the application may request promoted ongoing notifications.
    pub promotion_enabled: bool,
    /// Fine-grained Android presentation support.
    #[serde(default)]
    pub presentation: PresentationCapabilities,
}

impl Capabilities {
    /// Whether a regular notification may be posted, including as a fallback.
    #[must_use]
    pub fn can_post(&self) -> bool {
        self.notifications_enabled
    }

    /// Whether the application can request promoted ongoing presentation.
    #[must_use]
    pub fn can_promote(&self) -> bool {
        self.supported && self.can_post() && self.promotion_enabled
    }
}

/// Fine-grained Android system-presentation support. A member that a reply leaves out is false.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PresentationCapabilities {
    /// Standard notification style.
    pub standard: bool,
    /// Expanded BigTextStyle.
    pub big_text: bool,
    /// Up/down chronometer presentation.
    pub timer: bool,
    /// API 36 ProgressStyle.
    pub progress_style: bool,
    /// API 37 MetricStyle.
    pub metric_style: bool,
    /// Notification action buttons.
    pub actions: bool,
    /// Resource and app-private image sources.
    pub images: bool,
    /// Action and dismissal event draining.
    pub events: bool,
    /// Samsung API 37+ Now Bar extras.
    pub samsung_now_bar: bool,
    /// A header clock beside a style.
    pub chronometer: bool,
    /// URLs that the content tap and an action open in the application, and `opened` events.
    pub links: bool,
    /// Another system style in an update of a schema-version-2 activity.
    pub style_change: bool,
    /// `EndPolicy::After`: the last content stays until its time.
    pub delayed_end: bool,
    /// `Placement::Foreground`: a notification a foreground service of the host can hold.
    pub foreground_placement: bool,
    /// A revision that alerts again.
    pub alert: bool,
    /// A public version for the secure lock screen.
    pub public_version: bool,
    /// A small icon, an accent color and a sub text.
    pub branding: bool,
    /// `StartRequest::start_at`: a start that the adapter begins at its time, also while the
    /// application does not run.
    pub scheduled_start: bool,
    /// Whether the adapter may set an exact alarm for a scheduled start now. Without it Android
    /// chooses the moment, some minutes after the time.
    pub exact_start: bool,
}

/// What to do when the system cannot promote an ongoing notification.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Fallback {
    /// Return an unsupported-feature error without posting a notification.
    None,
    /// Post a regular ongoing notification using the same system template.
    Notification,
}

/// How much notification content the system may reveal on a secure lock screen.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Visibility {
    /// The adapter cannot prove what Android revealed to the user.
    Unknown,
    /// Reveal the full notification content.
    Public,
    /// Hide sensitive content until the device is unlocked.
    Private,
    /// Hide the notification entirely on a secure lock screen.
    Secret,
}

/// The promotion state observed after posting an ongoing notification.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PromotionState {
    /// The system reports that the notification is promoted.
    Promoted,
    /// The system accepted the notification without promoting it.
    NotPromoted,
    /// Promotion does not apply to this Android version or request.
    NotApplicable,
    /// A newer platform returned a state this crate does not yet recognize.
    #[serde(other)]
    Unknown,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    // LU-CAP-1
    #[test]
    fn posting_needs_notifications_and_promotion_needs_all_three() {
        for supported in [false, true] {
            for notifications_enabled in [false, true] {
                for promotion_enabled in [false, true] {
                    let capabilities = Capabilities {
                        supported,
                        notifications_enabled,
                        promotion_enabled,
                        presentation: PresentationCapabilities::default(),
                    };
                    assert_eq!(capabilities.can_post(), notifications_enabled);
                    assert_eq!(
                        capabilities.can_promote(),
                        supported && notifications_enabled && promotion_enabled
                    );
                }
            }
        }
    }

    // LU-CAP-5
    #[test]
    fn promotion_states_read_unknown_names_as_unknown() {
        for (name, state) in [
            ("promoted", PromotionState::Promoted),
            ("notPromoted", PromotionState::NotPromoted),
            ("notApplicable", PromotionState::NotApplicable),
            ("unknown", PromotionState::Unknown),
            ("demoted", PromotionState::Unknown),
        ] {
            assert_eq!(
                serde_json::from_value::<PromotionState>(json!(name)).unwrap(),
                state
            );
        }
        assert_eq!(
            serde_json::to_value(Visibility::Unknown).unwrap(),
            json!("unknown")
        );
        assert!(serde_json::from_value::<Visibility>(json!("hidden")).is_err());
        assert!(serde_json::from_value::<Fallback>(json!("silent")).is_err());
    }
}
