//! JSON bridge contract tests for managed Android adapters.

use liveupdate::{
    ActivityEvent, ActivityState, Bridge, Capabilities, Driver, DriverRecord, EndPolicy, Error,
    ErrorCode, EventBatch, EventKind, Fallback, JsonDriver, LiveUpdate, Observation,
    PresentationCapabilities, PromotionState, Snapshot, StartRequest, Template, Visibility,
};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    sync::{Arc, Mutex},
};

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
        dismiss_at: None,
        id: "activity-id".into(),
        origin: StartRequest {
            placement: liveupdate::Placement::Tagged,
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
        presentation: None,
    }
}

// LU-BRIDGE-1
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
        success(json!({
            "events": [{
                "sequence": 1,
                "activityId": "activity-id",
                "occurredAt": 1000,
                "kind": { "kind": "dismissed" }
            }],
            "dropped": 0
        })),
    ]);
    let driver = JsonDriver::new(bridge.clone());

    assert_eq!(
        driver.capabilities().unwrap(),
        Capabilities {
            supported: true,
            notifications_enabled: true,
            promotion_enabled: true,
            presentation: PresentationCapabilities::default(),
        }
    );
    assert_eq!(driver.read().unwrap(), vec![record()]);
    driver.write(&[record()]).unwrap();
    assert_eq!(driver.observe().unwrap(), vec![observed]);
    driver.post(&record()).unwrap();
    driver.cancel("activity-id").unwrap();
    assert_eq!(
        driver.drain_events().unwrap(),
        EventBatch {
            events: vec![ActivityEvent {
                sequence: 1,
                activity_id: "activity-id".into(),
                occurred_at: 1_000,
                kind: EventKind::Dismissed,
            }],
            dropped: 0,
        }
    );

    assert_eq!(
        bridge.calls(),
        vec![
            ("capabilities".into(), json!({})),
            ("read".into(), json!({})),
            ("write".into(), json!({ "records": [record()] })),
            ("observe".into(), json!({})),
            ("post".into(), json!({ "record": record() })),
            ("cancel".into(), json!({ "id": "activity-id" })),
            ("events".into(), json!({})),
        ]
    );
}

// LU-BRIDGE-4, LU-ERR-3
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
        r#"{"status":"error","error":{"code":"TIMEOUT","message":"x"}}"#,
        r#"{"status":"error","error":{"code":"CONFLICT","message":"x","cause":"y"}}"#,
        r#"{"status":"ok","data":{"supported":true},"extra":1}"#,
        r#"{"status":"ok","data":"supported"}"#,
        r#"{"status":"ok","data":null}"#,
        "not json",
        "",
    ] {
        let bridge = FakeBridge {
            replies: Arc::new(Mutex::new(vec![malformed.into()])),
            ..FakeBridge::default()
        };
        let error = JsonDriver::new(bridge).capabilities().unwrap_err();
        assert_eq!(error.code, ErrorCode::NativeError);
    }
}

// LU-BRIDGE-3
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

// LU-BRIDGE-4
#[test]
fn reply_data_may_gain_members_but_unit_replies_must_be_null() {
    let bridge = FakeBridge::returning([
        success(json!({
            "supported": false,
            "notificationsEnabled": true,
            "promotionEnabled": false,
            "futureFlag": true
        })),
        success(json!(1)),
    ]);
    let driver = JsonDriver::new(bridge);
    assert!(driver.capabilities().unwrap().can_post());
    assert_eq!(
        driver.cancel("activity-id").unwrap_err().code,
        ErrorCode::NativeError
    );
}

/// A bridge whose own transport fails.
struct Broken;

impl Bridge for Broken {
    fn invoke(&self, _command: &str, _payload: &str) -> liveupdate::Result<String> {
        Err(Error::new(
            ErrorCode::PermissionDenied,
            "Transport refused.",
        ))
    }
}

// LU-BRIDGE-5
#[test]
fn a_bridge_error_is_returned_as_it_is() {
    let error = JsonDriver::new(Broken).observe().unwrap_err();
    assert_eq!(
        error,
        Error::new(ErrorCode::PermissionDenied, "Transport refused.")
    );
    let driver = JsonDriver::new(FakeBridge::default());
    let _bridge: FakeBridge = driver.into_inner();
}

/// The managed adapter's JSON behavior: storage keeps the records as JSON, a post makes the
/// record's JSON observable, a cancel removes it.
#[derive(Clone, Default)]
struct JsonAdapter {
    stored: Arc<Mutex<Value>>,
    shown: Arc<Mutex<BTreeMap<String, Value>>>,
}

impl Bridge for JsonAdapter {
    fn invoke(&self, command: &str, payload: &str) -> liveupdate::Result<String> {
        let payload: Value = serde_json::from_str(payload).unwrap();
        let data = match command {
            "capabilities" => json!({
                "supported": true,
                "notificationsEnabled": true,
                "promotionEnabled": true
            }),
            "read" => {
                let stored = self.stored.lock().unwrap().clone();
                if stored.is_null() { json!([]) } else { stored }
            }
            "write" => {
                *self.stored.lock().unwrap() = payload["records"].clone();
                Value::Null
            }
            "observe" => Value::Array(
                self.shown
                    .lock()
                    .unwrap()
                    .values()
                    .map(|record| {
                        json!({
                            "id": record["id"],
                            "snapshot": record["snapshot"],
                            "state": "active",
                            "promotionRequested": record["promotionRequested"],
                            "promotionState": if record["promotionRequested"] == json!(true) {
                                "promoted"
                            } else {
                                "notApplicable"
                            },
                        })
                    })
                    .collect(),
            ),
            "post" => {
                let record = payload["record"].clone();
                let id = record["id"].as_str().unwrap().to_string();
                self.shown.lock().unwrap().insert(id, record);
                Value::Null
            }
            "cancel" => {
                self.shown
                    .lock()
                    .unwrap()
                    .remove(payload["id"].as_str().unwrap());
                Value::Null
            }
            _ => unreachable!("{command}"),
        };
        Ok(success(data).to_string())
    }
}

// LU-BRIDGE-1, LU-JNI-4
#[test]
fn a_client_over_the_json_bridge_runs_the_whole_lifecycle() {
    let adapter = JsonAdapter::default();
    let client = LiveUpdate::new(JsonDriver::new(adapter.clone()));
    let mut request = record().origin;
    request.snapshot.title = "🍡 \"Mochi\" \u{0}\u{2028}/\\ 中".into();
    request.snapshot.progress = Some(0.1 + 0.2);
    request.snapshot.revision = liveupdate::MAX_SAFE_INTEGER - 1;
    request.snapshot.stale_at = Some(liveupdate::MAX_TIMESTAMP);

    let started = client.start(request.clone()).unwrap();
    assert_eq!(started.state, ActivityState::Active);
    assert_eq!(started.promotion_state, PromotionState::Promoted);
    assert_eq!(started.snapshot, request.snapshot);
    let mut next = request.snapshot.clone();
    next.revision += 1;
    assert_eq!(
        client.update(&started.id, next).unwrap().state,
        ActivityState::Active
    );
    assert_eq!(client.list().unwrap().len(), 1);
    client.end(&started.id, EndPolicy::Immediate).unwrap();
    assert!(adapter.shown.lock().unwrap().is_empty());
    let stored = adapter.stored.lock().unwrap().clone();
    assert_eq!(stored[0]["state"], json!("ended"));
    assert_eq!(
        stored[0]["origin"]["snapshot"]["title"],
        json!(request.snapshot.title)
    );
}
