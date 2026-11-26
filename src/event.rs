use crate::{Error, ErrorCode, MAX_SAFE_INTEGER, MAX_TIMESTAMP, Result};
use serde::{Deserialize, Serialize};

/// Maximum number of Android events kept between drains.
pub const MAX_EVENTS: usize = 128;

/// What happened on an Android Live Activity notification. More kinds may come: match with a `_` arm.
#[non_exhaustive]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
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
    /// A tap that the application's own component took, an action's target: the application
    /// acted on it already.
    Handled {
        /// Stable action identifier from the snapshot.
        #[serde(rename = "actionId")]
        action_id: String,
    },
    /// What a data message from the app's server did ([`crate::push`]). The event's activity id is
    /// the activity's, and empty when the push named none.
    #[serde(rename_all = "camelCase")]
    Pushed {
        /// `start`, `update` or `end`.
        command: String,
        /// The key that the push named.
        activity_key: String,
        /// The snapshot's revision; none for an end.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        revision: Option<u64>,
        /// Why the lifecycle refused it; none when it did what it asked.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        code: Option<ErrorCode>,
    },
    /// A kind that this version does not know, from a later adapter.
    #[serde(other)]
    Unknown,
}

/// One sequenced event produced by Android. A later adapter may say more of it: what this version
/// does not know is not read.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
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
    /// An event, as a driver reports it.
    #[must_use]
    pub fn new(
        sequence: u64,
        activity_id: impl Into<String>,
        occurred_at: u64,
        kind: EventKind,
    ) -> Self {
        Self {
            sequence,
            activity_id: activity_id.into(),
            occurred_at,
            kind,
        }
    }

    /// Validates all transport bounds.
    pub fn validate(&self) -> Result<()> {
        // A push that named no activity has no id; every other event is of an activity.
        let unnamed = self.activity_id.is_empty() && matches!(self.kind, EventKind::Pushed { .. });
        if self.sequence > MAX_SAFE_INTEGER
            || self.occurred_at > MAX_TIMESTAMP
            || !(unnamed || crate::model::valid_id(&self.activity_id))
        {
            return Err(Error::invalid());
        }
        if let EventKind::Pushed {
            command,
            activity_key,
            revision,
            ..
        } = &self.kind
            && (!crate::push::COMMANDS.contains(&command.as_str())
                || !crate::model::valid_key(activity_key)
                || revision.is_some_and(|revision| revision > MAX_SAFE_INTEGER))
        {
            return Err(Error::invalid());
        }
        let action_id = match &self.kind {
            EventKind::Action { action_id } | EventKind::Handled { action_id } => Some(action_id),
            EventKind::Opened { action_id } => action_id.as_ref(),
            EventKind::Dismissed | EventKind::Pushed { .. } | EventKind::Unknown => None,
        };
        if action_id.is_some_and(|id| !valid_action_id(id)) {
            return Err(Error::invalid());
        }
        Ok(())
    }
}

/// Events returned and atomically removed by one drain.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
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
