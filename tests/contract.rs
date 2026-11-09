//! Compatibility tests for the serialized public contract.

use liveupdate::{
    ActivityState, Capabilities, DriverRecord, Fallback, Observation, PresentationCapabilities,
    PromotionState, Snapshot, StartRequest, Template, Timer, Visibility,
};
use serde_json::json;

// LU-CAP-1
#[test]
fn capabilities_use_the_stable_camel_case_contract() {
    let capabilities = Capabilities {
        supported: true,
        notifications_enabled: true,
        promotion_enabled: false,
        presentation: PresentationCapabilities::default(),
    };

    assert_eq!(
        serde_json::to_value(capabilities).unwrap(),
        json!({
            "supported": true,
            "notificationsEnabled": true,
            "promotionEnabled": false,
            "presentation": {
                "standard": false,
                "bigText": false,
                "timer": false,
                "progressStyle": false,
                "metricStyle": false,
                "actions": false,
                "images": false,
                "events": false,
                "samsungNowBar": false
            }
        })
    );
    assert_eq!(
        serde_json::from_value::<Capabilities>(json!({
            "supported": true,
            "notificationsEnabled": true,
            "promotionEnabled": false
        }))
        .unwrap(),
        capabilities
    );
}

// LU-CAP-1, LU-FALLBACK-1
#[test]
fn capability_helpers_separate_regular_posting_from_promotion() {
    let regular_notification = Capabilities {
        supported: true,
        notifications_enabled: true,
        promotion_enabled: false,
        presentation: PresentationCapabilities::default(),
    };
    assert!(regular_notification.can_post());
    assert!(!regular_notification.can_promote());

    let promoted = Capabilities {
        promotion_enabled: true,
        ..regular_notification
    };
    assert!(promoted.can_promote());

    let promotion_unsupported = Capabilities {
        supported: false,
        ..promoted
    };
    assert!(promotion_unsupported.can_post());
    assert!(!promotion_unsupported.can_promote());

    let notifications_disabled = Capabilities {
        notifications_enabled: false,
        ..promoted
    };
    assert!(!notifications_disabled.can_post());
    assert!(!notifications_disabled.can_promote());
}

// LU-BRIDGE-1
#[test]
fn platform_policy_enums_use_the_stable_contract() {
    for (value, expected) in [
        (serde_json::to_value(Fallback::None).unwrap(), json!("none")),
        (
            serde_json::to_value(Fallback::Notification).unwrap(),
            json!("notification"),
        ),
        (
            serde_json::to_value(Visibility::Public).unwrap(),
            json!("public"),
        ),
        (
            serde_json::to_value(Visibility::Private).unwrap(),
            json!("private"),
        ),
        (
            serde_json::to_value(Visibility::Secret).unwrap(),
            json!("secret"),
        ),
        (
            serde_json::to_value(PromotionState::Promoted).unwrap(),
            json!("promoted"),
        ),
        (
            serde_json::to_value(PromotionState::NotPromoted).unwrap(),
            json!("notPromoted"),
        ),
        (
            serde_json::to_value(PromotionState::NotApplicable).unwrap(),
            json!("notApplicable"),
        ),
        (
            serde_json::to_value(PromotionState::Unknown).unwrap(),
            json!("unknown"),
        ),
    ] {
        assert_eq!(value, expected);
    }
}

// LU-CAP-5
#[test]
fn an_unrecognized_promotion_state_is_forward_compatible() {
    let state: PromotionState = serde_json::from_str("\"futureState\"").unwrap();
    assert_eq!(state, PromotionState::Unknown);
}

// LU-BRIDGE-1
#[test]
fn records_cross_the_bridge_with_the_names_the_adapter_reads() {
    let snapshot = Snapshot {
        revision: 2,
        title: "Delivery".into(),
        status: "Soon".into(),
        progress: None,
        timer: Some(Timer {
            started_at: 10,
            ends_at: 20,
        }),
        stale_at: Some(30),
        presentation: None,
    };
    let record = DriverRecord {
        id: "id".into(),
        origin: StartRequest {
            activity_key: "delivery:1".into(),
            schema_version: 1,
            template: Template::Timer,
            fallback: Fallback::None,
            visibility: Visibility::Secret,
            snapshot: snapshot.clone(),
        },
        snapshot: snapshot.clone(),
        state: ActivityState::Unknown,
        promotion_requested: true,
        promotion_state: PromotionState::Unknown,
        awaiting_observation: true,
        post_failed: false,
    };
    let snapshot_json = json!({
        "revision": 2,
        "title": "Delivery",
        "status": "Soon",
        "timer": { "startedAt": 10, "endsAt": 20 },
        "staleAt": 30
    });
    assert_eq!(
        serde_json::to_value(&record).unwrap(),
        json!({
            "id": "id",
            "origin": {
                "activityKey": "delivery:1",
                "schemaVersion": 1,
                "template": "timer",
                "fallback": "none",
                "visibility": "secret",
                "snapshot": snapshot_json
            },
            "snapshot": snapshot_json,
            "state": "unknown",
            "promotionRequested": true,
            "promotionState": "unknown",
            "awaitingObservation": true,
            "postFailed": false
        })
    );
    let observation: Observation = serde_json::from_value(json!({
        "id": "id",
        "snapshot": snapshot_json,
        "state": "stale",
        "promotionRequested": true,
        "promotionState": "notPromoted"
    }))
    .unwrap();
    assert_eq!(observation.snapshot, snapshot);
    assert_eq!(observation.state, ActivityState::Stale);
}
