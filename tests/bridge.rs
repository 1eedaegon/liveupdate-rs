//! JSON bridge contract tests for managed Android adapters.

use liveupdate::{
    ActivityState, Bridge, Capabilities, Driver, DriverRecord, Error, ErrorCode, Fallback,
    JsonDriver, Observation, PromotionState, Snapshot, StartRequest, Template, Visibility,
};
use serde_json::{Value, json};
use std::sync::{Arc, Mutex};

#[derive(Clone, Default)]
struct FakeBridge {
    calls: Arc<Mutex<Vec<(String, Value)>>>,
    replies: Arc<Mutex<Vec<String>>>,
}

impl Bridge for FakeBridge {
    fn invoke(&self, command: &str, payload: &str) -> liveupdate::Result<String> {
        let payload = serde_json::from_str(payload)
            .map_err(|_| Error::new(ErrorCode::NativeError, "Invalid test payload."))?;
        self.calls.lock().unwrap().push((command.into(), payload));
        Ok(self.replies.lock().unwrap().remove(0))
    }
}

impl FakeBridge {
    fn returning(replies: impl IntoIterator<Item = Value>) -> Self {
        Self {
            replies: Arc::new(Mutex::new(
                replies.into_iter().map(|reply| reply.to_string()).collect(),
            )),
            ..Self::default()
        }
    }

    fn calls(&self) -> Vec<(String, Value)> {
        self.calls.lock().unwrap().clone()
    }
}

fn success(data: Value) -> Value {
    json!({ "status": "ok", "data": data })
}

fn record() -> DriverRecord {
    DriverRecord {
        id: "activity-id".into(),
        origin: StartRequest {
            activity_key: "delivery:1".into(),
            schema_version: 1,
            template: Template::Progress,
            fallback: Fallback::Notification,
            visibility: Visibility::Private,
            snapshot: snapshot(),
        },
        snapshot: snapshot(),
        state: ActivityState::Active,
        promotion_requested: true,
        promotion_state: PromotionState::Promoted,
        awaiting_observation: false,
        post_failed: false,
    }
}

fn snapshot() -> Snapshot {
    Snapshot {
        revision: 0,
        title: "Delivery".into(),
        status: "Preparing".into(),
        progress: Some(0.25),
        timer: None,
        stale_at: None,
    }
}

#[test]
fn every_driver_operation_uses_a_bounded_stable_command() {
    let observed = Observation {
        id: "activity-id".into(),
        snapshot: snapshot(),
        state: ActivityState::Active,
        promotion_requested: true,
        promotion_state: PromotionState::Promoted,
    };
    let bridge = FakeBridge::returning([
        success(json!({
            "supported": true,
            "notificationsEnabled": true,
            "promotionEnabled": true
        })),
        success(json!([record()])),
        success(Value::Null),
        success(json!([observed])),
        success(Value::Null),
        success(Value::Null),
    ]);
    let driver = JsonDriver::new(bridge.clone());

    assert_eq!(
        driver.capabilities().unwrap(),
        Capabilities {
            supported: true,
            notifications_enabled: true,
            promotion_enabled: true,
        }
    );
    assert_eq!(driver.read().unwrap(), vec![record()]);
    driver.write(&[record()]).unwrap();
    assert_eq!(driver.observe().unwrap(), vec![observed]);
    driver.post(&record()).unwrap();
    driver.cancel("activity-id").unwrap();

    assert_eq!(
        bridge.calls(),
        vec![
            ("capabilities".into(), json!({})),
            ("read".into(), json!({})),
            ("write".into(), json!({ "records": [record()] })),
            ("observe".into(), json!({})),
            ("post".into(), json!({ "record": record() })),
            ("cancel".into(), json!({ "id": "activity-id" })),
        ]
    );
}

#[test]
fn adapter_errors_are_preserved_without_accepting_malformed_envelopes() {
    let denied = FakeBridge::returning([json!({
        "status": "error",
        "error": {
            "code": "PERMISSION_DENIED",
            "message": "Notifications are disabled."
        }
    })]);
    let error = JsonDriver::new(denied).capabilities().unwrap_err();
    assert_eq!(error.code, ErrorCode::PermissionDenied);

    for malformed in [
        "{}",
        r#"{"status":"ok"}"#,
        r#"{"status":"future","data":null}"#,
        r#"{"status":"error","error":{"code":"NATIVE_ERROR","message":"x"},"extra":1}"#,
    ] {
        let bridge = FakeBridge {
            replies: Arc::new(Mutex::new(vec![malformed.into()])),
            ..FakeBridge::default()
        };
        let error = JsonDriver::new(bridge).capabilities().unwrap_err();
        assert_eq!(error.code, ErrorCode::NativeError);
    }
}

#[test]
fn oversized_adapter_replies_fail_closed() {
    let bridge = FakeBridge {
        replies: Arc::new(Mutex::new(vec!["x".repeat(1_048_577)])),
        ..FakeBridge::default()
    };

    assert_eq!(
        JsonDriver::new(bridge).capabilities().unwrap_err().code,
        ErrorCode::NativeError
    );
}
