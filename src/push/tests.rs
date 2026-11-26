use super::*;
use crate::{AndroidPresentation, PresentationStyle};

// LU-PUSH-7
#[test]
fn the_outcome_names_the_push_and_its_activity_or_its_refusal() {
    let snapshot = Snapshot {
        presentation: Some(AndroidPresentation::new(PresentationStyle::Standard)),
        ..Snapshot::new(3, "Ride", "On the way")
    };
    let update = Payload::update("ride:1", snapshot);
    assert_eq!(
        payload_outcome(Some(&update), Ok("a1")),
        json!({ "command": "update", "activityKey": "ride:1", "revision": 3, "id": "a1" })
    );
    assert_eq!(
        payload_outcome(Some(&update), Err(ErrorCode::StaleRevision)),
        json!({ "command": "update", "activityKey": "ride:1", "revision": 3, "code": "STALE_REVISION" })
    );
    let end = Payload::end("ride:1", EndPolicy::Immediate);
    assert_eq!(
        payload_outcome(Some(&end), Err(ErrorCode::NotFound)),
        json!({ "command": "end", "activityKey": "ride:1", "code": "NOT_FOUND" })
    );
    // A member that is not a payload names nothing: the AAR stores no event for it.
    assert_eq!(
        payload_outcome(None, Err(ErrorCode::InvalidInput)),
        json!({ "code": "INVALID_INPUT" })
    );
    assert_eq!(
        payload_outcome(None, Ok("a1")),
        json!({ "code": "INVALID_INPUT" })
    );
}
