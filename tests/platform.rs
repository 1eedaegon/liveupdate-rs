//! What Android's notification guides name beside a Live Update: an activity kept off a paired watch,
//! and the settings screen of the application's Live Updates.

mod common;

use common::{FakeDriver, assert_code};
use liveupdate::{
    AndroidPresentation, Bridge, Error, ErrorCode, Fallback, JsonDriver, LiveUpdate,
    PresentationStyle, Snapshot, StartRequest, Visibility,
};
use serde_json::{Value, json};
use std::sync::{Arc, Mutex};

fn kept_local(local_only: bool) -> Snapshot {
    Snapshot {
        presentation: Some(AndroidPresentation {
            local_only,
            ..AndroidPresentation::new(PresentationStyle::Standard)
        }),
        ..Snapshot::new(0, "Ride", "On the way")
    }
}

// LU-PLAT-1
#[test]
fn an_activity_may_stay_off_a_paired_watch_and_is_left_out_when_it_does_not() {
    assert!(!AndroidPresentation::new(PresentationStyle::Standard).local_only);
    let written = serde_json::to_value(kept_local(false)).unwrap();
    assert!(written["presentation"].get("localOnly").is_none());
    let written = serde_json::to_value(kept_local(true)).unwrap();
    assert_eq!(written["presentation"]["localOnly"], json!(true));
    assert_eq!(
        serde_json::from_value::<Snapshot>(written).unwrap(),
        kept_local(true)
    );
}

// LU-PLAT-1
#[test]
fn an_activity_kept_off_the_watch_needs_the_adapters_capability() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let request = StartRequest::new(
        "ride",
        Fallback::Notification,
        Visibility::Public,
        kept_local(true),
    );

    // An adapter that does not report it would send the activity to the watch.
    assert_code(client.start(request.clone()), ErrorCode::UnsupportedFeature);
    assert!(driver.records().is_empty());
    let plain = client
        .start(StartRequest::new(
            "ride",
            Fallback::Notification,
            Visibility::Public,
            kept_local(false),
        ))
        .unwrap();
    assert_code(
        client.update(
            &plain.id,
            Snapshot {
                revision: 1,
                ..kept_local(true)
            },
        ),
        ErrorCode::UnsupportedFeature,
    );

    driver.edit(|state| state.capabilities.presentation.local_only = true);
    let updated = client
        .update(
            &plain.id,
            Snapshot {
                revision: 1,
                ..kept_local(true)
            },
        )
        .unwrap();
    assert_eq!(updated.snapshot.revision, 1);
}

#[derive(Clone, Default)]
struct Recorded {
    calls: Arc<Mutex<Vec<(String, Value)>>>,
    reply: Arc<Mutex<Option<String>>>,
}

impl Bridge for Recorded {
    fn invoke(&self, command: &str, payload: &str) -> liveupdate::Result<String> {
        let payload = serde_json::from_str(payload)
            .map_err(|_| Error::new(ErrorCode::NativeError, "Invalid test payload."))?;
        self.calls.lock().unwrap().push((command.into(), payload));
        Ok(self.reply.lock().unwrap().clone().unwrap_or_default())
    }
}

// LU-PLAT-2
#[test]
fn the_settings_of_promotion_open_through_the_bridge_and_a_driver_without_them_says_so() {
    let bridge = Recorded::default();
    *bridge.reply.lock().unwrap() = Some(json!({ "status": "ok", "data": null }).to_string());
    LiveUpdate::new(JsonDriver::new(bridge.clone()))
        .open_promotion_settings()
        .unwrap();
    assert_eq!(
        bridge.calls.lock().unwrap().clone(),
        vec![("settings".to_string(), json!({}))]
    );

    // An adapter of an earlier version does not know the command.
    *bridge.reply.lock().unwrap() = Some(
        json!({ "status": "error", "error": { "code": "UNSUPPORTED_FEATURE", "message": "x" } })
            .to_string(),
    );
    assert_code(
        LiveUpdate::new(JsonDriver::new(bridge)).open_promotion_settings(),
        ErrorCode::UnsupportedFeature,
    );

    // A driver that does not implement it keeps compiling, and answers the same.
    assert_code(
        LiveUpdate::new(FakeDriver::default()).open_promotion_settings(),
        ErrorCode::UnsupportedFeature,
    );
}
