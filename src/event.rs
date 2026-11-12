use crate::{Error, MAX_SAFE_INTEGER, MAX_TIMESTAMP, Result};
use serde::{Deserialize, Serialize};

/// Maximum number of Android events kept between drains.
pub const MAX_EVENTS: usize = 128;

/// What happened on an Android Live Activity notification. More kinds may come: match with a `_` arm.
#[non_exhaustive]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase", deny_unknown_fields)]
pub enum EventKind {
    /// The user selected a declared notification action.
    Action {
        /// Stable action identifier from the snapshot.
        #[serde(rename = "actionId")]
        action_id: String,
    },
    /// The user or system dismissed the notification.
    Dismissed,
    /// The user opened the application from the notification, as the opened activity reported.
    Opened {
        /// The opening action, when the user tapped one instead of the content.
        #[serde(rename = "actionId", default, skip_serializing_if = "Option::is_none")]
        action_id: Option<String>,
    },
}

/// One sequenced event produced by Android.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ActivityEvent {
    /// Monotonic adapter-owned sequence.
    pub sequence: u64,
    /// Activity handle whose notification produced the event.
    pub activity_id: String,
    /// Device wall-clock time in milliseconds.
    pub occurred_at: u64,
    /// Event kind and its typed data.
    pub kind: EventKind,
}

impl ActivityEvent {
    /// Validates all transport bounds.
    pub fn validate(&self) -> Result<()> {
        if self.sequence > MAX_SAFE_INTEGER
            || self.occurred_at > MAX_TIMESTAMP
            || !crate::model::valid_id(&self.activity_id)
        {
            return Err(Error::invalid());
        }
        let action_id = match &self.kind {
            EventKind::Action { action_id } => Some(action_id),
            EventKind::Opened { action_id } => action_id.as_ref(),
            EventKind::Dismissed => None,
        };
        if action_id.is_some_and(|id| !valid_action_id(id)) {
            return Err(Error::invalid());
        }
        Ok(())
    }
}

/// Events returned and atomically removed by one drain.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EventBatch {
    /// Oldest-to-newest events.
    pub events: Vec<ActivityEvent>,
    /// Number of oldest events discarded since the previous drain.
    pub dropped: u64,
}

impl EventBatch {
    /// Validates batch bounds and strict sequence ordering.
    pub fn validate(&self) -> Result<()> {
        if self.events.len() > MAX_EVENTS || self.dropped > MAX_SAFE_INTEGER {
            return Err(Error::invalid());
        }
        let mut previous = None;
        for event in &self.events {
            event.validate()?;
            if previous.is_some_and(|sequence| event.sequence <= sequence) {
                return Err(Error::invalid());
            }
            previous = Some(event.sequence);
        }
        Ok(())
    }
}

fn valid_action_id(value: &str) -> bool {
    let mut characters = value.chars();
    let Some(first) = characters.next() else {
        return false;
    };
    value.chars().count() <= 64
        && first.is_ascii_alphanumeric()
        && characters.all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | ':' | '-')
        })
}
