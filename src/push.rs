//! Activities from a push: what an app's server sends in a data message (FCM's, or any provider's
//! that delivers a map of strings), and what the device runs through the lifecycle with
//! [`LiveUpdate::push`](crate::LiveUpdate::push).
//!
//! A server builds the map with [`Payload::to_data`]. On Android the AAR's
//! `LiveUpdatePush.handle(context, message.data)` hands the member to the lifecycle while the app does
//! not run; a host in Rust that receives the member itself calls [`Payload::parse`] and
//! [`LiveUpdate::push`](crate::LiveUpdate::push).
//!
//! ```
//! use liveupdate::{AndroidPresentation, PresentationStyle, Snapshot, push::{DATA_KEY, Payload}};
//!
//! let snapshot = Snapshot {
//!     presentation: Some(AndroidPresentation::new(PresentationStyle::Standard)),
//!     ..Snapshot::new(3, "Ride", "2 minutes away")
//! };
//! let data = Payload::update("ride:42", snapshot).to_data()?;
//! assert!(data[DATA_KEY].contains("\"command\":\"update\""));
//! # Ok::<(), liveupdate::Error>(())
//! ```

use crate::{EndPolicy, Error, ErrorCode, Result, Snapshot, StartRequest, model::valid_key};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::BTreeMap;

/// The member of a data map that carries a payload.
pub const DATA_KEY: &str = "liveupdate";

/// The bytes of UTF-8 that FCM carries in a message's data, its keys and values together.
pub const MAX_DATA_BYTES: usize = 4096;

/// The commands a payload may carry, as an event names them.
pub(crate) const COMMANDS: [&str; 3] = ["start", "update", "end"];

const VERSION: u64 = 1;

/// What a server asks of an activity on the device. More commands may come: match with a `_` arm.
#[derive(Debug, Clone, PartialEq)]
#[non_exhaustive]
pub enum Payload {
    /// Begins an activity, as [`LiveUpdate::start`](crate::LiveUpdate::start).
    Start(StartRequest),
    /// Updates the live activity of a key, as [`LiveUpdate::update`](crate::LiveUpdate::update).
    Update {
        /// The key the activity was started with.
        activity_key: String,
        /// The new content, with a revision that the server owns.
        snapshot: Snapshot,
    },
    /// Ends the live activity of a key, as [`LiveUpdate::end`](crate::LiveUpdate::end).
    End {
        /// The key the activity was started with.
        activity_key: String,
        /// How it ends.
        policy: EndPolicy,
    },
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Wire {
    v: u64,
    command: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    request: Option<StartRequest>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    activity_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    snapshot: Option<Snapshot>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    policy: Option<EndPolicy>,
}

impl Payload {
    /// A start of `request`.
    #[must_use]
    pub fn start(request: StartRequest) -> Self {
        Self::Start(request)
    }

    /// An update of the live activity of `activity_key`.
    #[must_use]
    pub fn update(activity_key: impl Into<String>, snapshot: Snapshot) -> Self {
        Self::Update {
            activity_key: activity_key.into(),
            snapshot,
        }
    }

    /// An end of the live activity of `activity_key`.
    #[must_use]
    pub fn end(activity_key: impl Into<String>, policy: EndPolicy) -> Self {
        Self::End {
            activity_key: activity_key.into(),
            policy,
        }
    }

    /// `start`, `update` or `end`.
    #[must_use]
    pub fn command(&self) -> &'static str {
        match self {
            Self::Start(_) => "start",
            Self::Update { .. } => "update",
            Self::End { .. } => "end",
        }
    }

    /// The key that the payload names.
    #[must_use]
    pub fn activity_key(&self) -> &str {
        match self {
            Self::Start(request) => &request.activity_key,
            Self::Update { activity_key, .. } | Self::End { activity_key, .. } => activity_key,
        }
    }

    /// The snapshot's revision; none for an end.
    #[must_use]
    pub fn revision(&self) -> Option<u64> {
        match self {
            Self::Start(request) => Some(request.snapshot.revision),
            Self::Update { snapshot, .. } => Some(snapshot.revision),
            Self::End { .. } => None,
        }
    }

    /// Checks what the lifecycle checks before it reads the receipts. What only the device knows,
    /// such as the start's schema against an update or the adapter's capabilities, it checks then.
    pub fn validate(&self) -> Result<()> {
        match self {
            Self::Start(request) => request.validate(),
            Self::Update {
                activity_key,
                snapshot,
            } => {
                if !valid_key(activity_key) {
                    return Err(Error::invalid());
                }
                snapshot.validate()
            }
            Self::End {
                activity_key,
                policy,
            } => {
                if !valid_key(activity_key) {
                    return Err(Error::invalid());
                }
                (*policy).validate()
            }
        }
    }

    /// The data map of a message: the one member [`DATA_KEY`]. Refused, as by the lifecycle, when the
    /// payload is invalid, and when the map is over [`MAX_DATA_BYTES`].
    pub fn to_data(&self) -> Result<BTreeMap<String, String>> {
        self.validate()?;
        let (request, activity_key, snapshot, policy) = match self.clone() {
            Self::Start(request) => (Some(request), None, None, None),
            Self::Update {
                activity_key,
                snapshot,
            } => (None, Some(activity_key), Some(snapshot), None),
            Self::End {
                activity_key,
                policy,
            } => (None, Some(activity_key), None, Some(policy)),
        };
        let wire = Wire {
            v: VERSION,
            command: self.command().into(),
            request,
            activity_key,
            snapshot,
            policy,
        };
        let value = serde_json::to_string(&wire).map_err(|_| Error::invalid())?;
        let bytes = DATA_KEY.len() + value.len();
        if bytes > MAX_DATA_BYTES {
            return Err(Error::new(
                ErrorCode::InvalidInput,
                format!("The payload is {bytes} bytes: a data message carries {MAX_DATA_BYTES}."),
            ));
        }
        Ok(BTreeMap::from([(DATA_KEY.into(), value)]))
    }

    /// Reads the member [`DATA_KEY`] of a message, and checks it as [`Payload::to_data`] does: the
    /// device trusts no server. A member that a later server adds is left alone.
    pub fn parse(member: &str) -> Result<Self> {
        let wire: Wire = serde_json::from_str(member).map_err(|_| Error::invalid())?;
        if wire.v != VERSION {
            return Err(Error::invalid());
        }
        let payload = match (
            wire.command.as_str(),
            wire.request,
            wire.activity_key,
            wire.snapshot,
            wire.policy,
        ) {
            ("start", Some(request), None, None, None) => Self::Start(request),
            ("update", None, Some(activity_key), Some(snapshot), None) => Self::Update {
                activity_key,
                snapshot,
            },
            ("end", None, Some(activity_key), None, Some(policy)) => Self::End {
                activity_key,
                policy,
            },
            _ => return Err(Error::invalid()),
        };
        payload.validate()?;
        Ok(payload)
    }
}

/// What the native entry answers the AAR: what the push asked and how it went, which the AAR stores
/// as a `pushed` event. A member that is not a payload has its code alone, and no event.
#[cfg_attr(
    not(all(feature = "push", target_os = "android")),
    allow(
        dead_code,
        reason = "the native entry is Android's, with the feature `push`"
    )
)]
pub(crate) fn outcome(
    payload: Option<&Payload>,
    result: std::result::Result<&str, ErrorCode>,
) -> Value {
    let mut outcome = serde_json::Map::new();
    if let Some(payload) = payload {
        outcome.insert("command".into(), payload.command().into());
        outcome.insert("activityKey".into(), payload.activity_key().into());
        if let Some(revision) = payload.revision() {
            outcome.insert("revision".into(), revision.into());
        }
    }
    match (payload, result) {
        (Some(_), Ok(id)) => {
            outcome.insert("id".into(), id.into());
        }
        (None, Ok(_)) => {
            outcome.insert("code".into(), json!(ErrorCode::InvalidInput));
        }
        (_, Err(code)) => {
            outcome.insert("code".into(), json!(code));
        }
    }
    Value::Object(outcome)
}

#[cfg(test)]
mod tests;
