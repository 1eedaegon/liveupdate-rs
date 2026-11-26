//! What an app's server sends for a ride: the data of the messages that begin, update and end its
//! activity on the phone, also while the app does not run. It builds on any target.

#![deny(clippy::unwrap_used, clippy::expect_used)]

use liveupdate::{
    AndroidPresentation, EndPolicy, Fallback, PresentationStyle, Result, Snapshot, StartRequest,
    Visibility, push::Payload,
};
use std::collections::BTreeMap;

fn ride(revision: u64, status: &str) -> Snapshot {
    Snapshot {
        presentation: Some(AndroidPresentation::new(PresentationStyle::Standard)),
        ..Snapshot::new(revision, "Ride", status)
    }
}

/// The data of each message, in the order the server sends them: one member, `liveupdate`.
pub fn ride_messages() -> Result<Vec<BTreeMap<String, String>>> {
    let key = "ride:42";
    Ok(vec![
        Payload::start(StartRequest::new(
            key,
            Fallback::Notification,
            Visibility::Public,
            ride(0, "Driver assigned"),
        ))
        .to_data()?,
        Payload::update(key, ride(1, "2 minutes away")).to_data()?,
        Payload::end(key, EndPolicy::Immediate).to_data()?,
    ])
}
