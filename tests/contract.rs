//! Compatibility tests for the serialized public contract.

use liveupdate::{
    ActivityRecord, ActivityState, Capabilities, DriverRecord, Fallback, Observation,
    PresentationCapabilities, PromotionState, Snapshot, StartRequest, Template, Timer, Visibility,
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
                "samsungNowBar": false,
                "chronometer": false,
                "links": false,
                "styleChange": false,
                "delayedEnd": false,
                "foregroundPlacement": false,
                "alert": false,
                "publicVersion": false,
                "branding": false
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

// LU-V2-CAP-2
#[test]
fn an_extension_a_reply_does_not_name_is_not_available() {
    let reply = json!({
        "supported": true,
        "notificationsEnabled": true,
        "promotionEnabled": true,
        "presentation": { "standard": true, "progressStyle": true }
    });
    let capabilities: Capabilities = serde_json::from_value(reply).unwrap();
    assert!(capabilities.presentation.standard);
    assert!(capabilities.presentation.progress_style);
    assert!(!capabilities.presentation.metric_style);
    let extensions = |capabilities: &Capabilities| {
        let presentation = capabilities.presentation;
        [
            presentation.chronometer,
            presentation.links,
            presentation.style_change,
            presentation.delayed_end,
            presentation.foreground_placement,
            presentation.alert,
            presentation.public_version,
            presentation.branding,
        ]
    };
    assert_eq!(extensions(&capabilities), [false; 8]);

    let names = [
        "chronometer",
        "links",
        "styleChange",
        "delayedEnd",
        "foregroundPlacement",
        "alert",
        "publicVersion",
        "branding",
    ];
    for (index, name) in names.into_iter().enumerate() {
        let with: Capabilities = serde_json::from_value(json!({
            "supported": true,
            "notificationsEnabled": true,
            "promotionEnabled": true,
            "presentation": { name: true }
        }))
        .unwrap();
        let mut expected = [false; 8];
        expected[index] = true;
        assert_eq!(extensions(&with), expected, "{name}");
        assert!(!with.presentation.standard);
    }
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
        reported_state: None,
        dismiss_at: None,
        id: "id".into(),
        origin: StartRequest {
            placement: liveupdate::Placement::Tagged,
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

// LU-BRIDGE-1
#[test]
fn public_records_do_not_leak_cross_platform_backend_discriminators() {
    let record = ActivityRecord {
        id: "id".into(),
        activity_key: "delivery:1".into(),
        schema_version: 1,
        template: Template::Progress,
        snapshot: Snapshot {
            revision: 2,
            title: "Delivery".into(),
            status: "Soon".into(),
            progress: Some(0.5),
            timer: None,
            stale_at: None,
            presentation: None,
        },
        state: ActivityState::Active,
        visibility: Visibility::Unknown,
        degraded_features: vec!["nativeLiveActivity".into()],
        promotion_requested: true,
        promotion_state: PromotionState::NotPromoted,
    };

    assert_eq!(
        serde_json::to_value(record).unwrap(),
        json!({
            "id": "id",
            "activityKey": "delivery:1",
            "schemaVersion": 1,
            "template": "progress",
            "snapshot": {
                "revision": 2,
                "title": "Delivery",
                "status": "Soon",
                "progress": 0.5
            },
            "state": "active",
            "visibility": "unknown",
            "degradedFeatures": ["nativeLiveActivity"],
            "promotionRequested": true,
            "promotionState": "notPromoted"
        })
    );
}
