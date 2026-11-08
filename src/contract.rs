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
