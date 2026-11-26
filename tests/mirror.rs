//! A phone's activity mirrored to a paired watch: a second notification, not ongoing, that the
//! watch's bridge takes.

mod common;

use common::{FakeDriver, assert_code};
use liveupdate::{
    AndroidPresentation, ErrorCode, Fallback, LiveUpdate, PresentationStyle, Snapshot,
    StartRequest, Visibility,
};
use serde_json::json;

fn mirrored(watch_mirror: bool, local_only: bool) -> Snapshot {
    Snapshot {
        presentation: Some(AndroidPresentation {
            watch_mirror,
            local_only,
            ..AndroidPresentation::new(PresentationStyle::Standard)
        }),
        ..Snapshot::new(0, "Ride", "On the way")
    }
}

fn start(snapshot: Snapshot) -> StartRequest {
    StartRequest::new("ride", Fallback::Notification, Visibility::Public, snapshot)
}

// LU-MIRROR-1
#[test]
fn an_activity_may_be_mirrored_to_a_watch_and_is_left_out_when_it_is_not() {
    assert!(!AndroidPresentation::new(PresentationStyle::Standard).watch_mirror);
    let written = serde_json::to_value(mirrored(false, false)).unwrap();
    assert!(written["presentation"].get("watchMirror").is_none());
    let written = serde_json::to_value(mirrored(true, false)).unwrap();
    assert_eq!(written["presentation"]["watchMirror"], json!(true));
    assert_eq!(
        serde_json::from_value::<Snapshot>(written).unwrap(),
        mirrored(true, false)
    );
}

// LU-MIRROR-1
#[test]
fn a_mirror_needs_the_adapters_capability() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    // A watch, or an adapter before the mirror, would show the activity on the phone alone.
    assert_code(
        client.start(start(mirrored(true, false))),
        ErrorCode::UnsupportedFeature,
    );
    assert!(driver.records().is_empty());
    let plain = client.start(start(mirrored(false, false))).unwrap();
    assert_code(
        client.update(
            &plain.id,
            Snapshot {
                revision: 1,
                ..mirrored(true, false)
            },
        ),
        ErrorCode::UnsupportedFeature,
    );
    driver.edit(|state| state.capabilities.presentation.watch_mirror = true);
    let updated = client
        .update(
            &plain.id,
            Snapshot {
                revision: 1,
                ..mirrored(true, false)
            },
        )
        .unwrap();
    assert_eq!(updated.snapshot.revision, 1);
}

// LU-MIRROR-2
#[test]
fn an_activity_kept_off_a_paired_device_is_not_mirrored_to_one() {
    let both = mirrored(true, true);
    assert_code(both.validate(), ErrorCode::InvalidInput);
    let driver = FakeDriver::default();
    driver.edit(|state| {
        state.capabilities.presentation.watch_mirror = true;
        state.capabilities.presentation.local_only = true;
    });
    assert_code(
        LiveUpdate::new(driver).start(start(both)),
        ErrorCode::InvalidInput,
    );
}
