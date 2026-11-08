//! Compatibility tests for the serialized public contract.

use liveupdate::{Capabilities, Fallback, PromotionState, Visibility};
use serde_json::json;

#[test]
fn capabilities_use_the_stable_camel_case_contract() {
    let capabilities = Capabilities {
        supported: true,
        notifications_enabled: true,
        promotion_enabled: false,
    };

    assert_eq!(
        serde_json::to_value(capabilities).unwrap(),
        json!({
            "supported": true,
            "notificationsEnabled": true,
            "promotionEnabled": false
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

#[test]
fn an_unrecognized_promotion_state_is_forward_compatible() {
    let state: PromotionState = serde_json::from_str("\"futureState\"").unwrap();
    assert_eq!(state, PromotionState::Unknown);
}
