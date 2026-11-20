//! An action whose tap Android delivers to a component of the application itself, and the `handled`
//! event that records a tap the application took.

mod common;

use common::{FakeDriver, assert_code};
use liveupdate::{
    ActionTarget, ActivityEvent, AndroidPresentation, ErrorCode, EventBatch, EventKind, Fallback,
    LiveUpdate, NotificationAction, PresentationStyle, Snapshot, StartRequest, TargetKind,
    Visibility,
};
use serde_json::{Value, json};

const SERVICE: &str = "dev.fremen.app.FremenService";

fn record(target: Option<ActionTarget>) -> NotificationAction {
    NotificationAction {
        target,
        ..NotificationAction::new("record", "Record")
    }
}

fn presented(revision: u64, actions: Vec<NotificationAction>) -> Snapshot {
    Snapshot {
        presentation: Some(AndroidPresentation {
            actions,
            ..AndroidPresentation::new(PresentationStyle::Standard)
        }),
        ..Snapshot::new(revision, "Fremen", "Listening")
    }
}

fn start(revision_actions: Vec<NotificationAction>) -> StartRequest {
    StartRequest::new(
        "fremen:session",
        Fallback::Notification,
        Visibility::Public,
        presented(0, revision_actions),
    )
}

fn targeted(kind: TargetKind) -> ActionTarget {
    ActionTarget {
        action: Some("dev.fremen.app.RECORD".into()),
        ..ActionTarget::new(kind, SERVICE)
    }
}

// LU-TARGET-1
#[test]
fn a_target_has_a_stable_shape_and_is_left_out_when_unset() {
    assert_eq!(
        ActionTarget::new(TargetKind::Service, SERVICE),
        ActionTarget {
            kind: TargetKind::Service,
            component: SERVICE.into(),
            action: None,
        }
    );
    assert_eq!(NotificationAction::new("record", "Record").target, None);
    assert_eq!(
        serde_json::to_value(NotificationAction::new("record", "Record")).unwrap(),
        json!({ "id": "record", "title": "Record" })
    );
    for (kind, name) in [
        (TargetKind::Service, "service"),
        (TargetKind::ForegroundService, "foregroundService"),
        (TargetKind::Broadcast, "broadcast"),
    ] {
        let action = record(Some(targeted(kind)));
        let written = serde_json::to_value(&action).unwrap();
        assert_eq!(
            written,
            json!({
                "id": "record",
                "title": "Record",
                "target": { "kind": name, "component": SERVICE, "action": "dev.fremen.app.RECORD" }
            })
        );
        assert_eq!(
            serde_json::from_value::<NotificationAction>(written).unwrap(),
            action
        );
    }
    let without_action = serde_json::to_value(ActionTarget::new(TargetKind::Broadcast, SERVICE));
    assert_eq!(
        without_action.unwrap(),
        json!({ "kind": "broadcast", "component": SERVICE })
    );
    // A request is the application's: what it does not know is a mistake where it was written.
    for wrong in [
        json!({ "kind": "activity", "component": SERVICE }),
        json!({ "kind": "service", "component": SERVICE, "package": "dev.other" }),
        json!({ "kind": "service" }),
    ] {
        assert!(serde_json::from_value::<ActionTarget>(wrong).is_err());
    }
}

// LU-TARGET-1
#[test]
fn a_target_names_a_class_and_an_intent_action_and_is_never_beside_a_link() {
    let valid = |target: ActionTarget| {
        StartRequest::new(
            "fremen:session",
            Fallback::Notification,
            Visibility::Public,
            presented(0, vec![record(Some(target))]),
        )
        .validate()
    };
    for component in [
        SERVICE,
        "a.B",
        "dev.fremen.app.FremenService$Inner",
        "_x.y",
        &format!("a.{}", "b".repeat(254)),
    ] {
        assert!(
            valid(ActionTarget::new(TargetKind::Service, component)).is_ok(),
            "{component}"
        );
    }
    for component in [
        "",
        "FremenService",
        ".FremenService",
        "dev.fremen.",
        "dev..FremenService",
        "dev.1fremen.Service",
        "dev.fremen.Fremen Service",
        "dev/fremen/FremenService",
        &format!("a.{}", "b".repeat(255)),
        // ASCII only, as the AAR checks it: Rust and Java do not agree on Unicode's letters.
        "dev.fremen.app.Ünicode",
        "dev.fremen.app.Ⅷ",
    ] {
        assert_eq!(
            valid(ActionTarget::new(TargetKind::Service, component))
                .unwrap_err()
                .code,
            ErrorCode::InvalidInput,
            "{component:?}"
        );
    }
    let with_action = |action: &str| ActionTarget {
        action: Some(action.into()),
        ..ActionTarget::new(TargetKind::Service, SERVICE)
    };
    for action in [
        "dev.fremen.app.RECORD",
        "RECORD",
        "a-b_c:d",
        &"A".repeat(256),
    ] {
        assert!(valid(with_action(action)).is_ok(), "{action}");
    }
    for action in ["", " RECORD", "RE CORD", "RECORDÜ", &"A".repeat(257)] {
        assert_eq!(
            valid(with_action(action)).unwrap_err().code,
            ErrorCode::InvalidInput,
            "{action:?}"
        );
    }

    // One destination: a target and a link together are refused.
    let both = NotificationAction {
        open_url: Some("fremen://session".into()),
        ..record(Some(ActionTarget::new(TargetKind::Service, SERVICE)))
    };
    let request = start(vec![both]);
    assert_eq!(
        request.validate().unwrap_err().code,
        ErrorCode::InvalidInput
    );
}

// LU-TARGET-2
#[test]
fn an_action_with_a_target_needs_the_adapters_capability() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());

    // An adapter that does not report it would send the tap to the queue: nothing is stored or posted.
    let targeted_start = start(vec![record(Some(targeted(TargetKind::ForegroundService)))]);
    assert_code(
        client.start(targeted_start.clone()),
        ErrorCode::UnsupportedFeature,
    );
    assert!(driver.records().is_empty());
    assert_eq!(driver.counts().0, 0);

    // Without a target the same start is taken, and an update that adds one is refused.
    let plain = client.start(start(vec![record(None)])).unwrap();
    assert_code(
        client.update(
            &plain.id,
            presented(1, vec![record(Some(targeted(TargetKind::Service)))]),
        ),
        ErrorCode::UnsupportedFeature,
    );
    assert_eq!(driver.records()[0].snapshot.revision, 0);
    assert_eq!(driver.counts().0, 1);

    // Offered, the target is stored and posted as it was given.
    driver.edit(|state| state.capabilities.presentation.action_targets = true);
    let updated = client
        .update(
            &plain.id,
            presented(1, vec![record(Some(targeted(TargetKind::Service)))]),
        )
        .unwrap();
    assert_eq!(updated.snapshot.revision, 1);
    let posted = driver.edit(|state| state.posted.last().cloned()).unwrap();
    let written = serde_json::to_value(&posted.snapshot).unwrap();
    assert_eq!(
        written["presentation"]["actions"][0]["target"],
        json!({ "kind": "service", "component": SERVICE, "action": "dev.fremen.app.RECORD" })
    );
    let other = client
        .start(StartRequest::new(
            "fremen:other",
            Fallback::Notification,
            Visibility::Public,
            presented(0, vec![record(Some(targeted(TargetKind::Broadcast)))]),
        ))
        .unwrap();
    assert_eq!(other.snapshot.revision, 0);
}

// LU-V2-EVENT-8
#[test]
fn a_tap_that_the_application_took_is_a_handled_event() {
    let handled = ActivityEvent::new(
        3,
        "activity-id",
        1_000,
        EventKind::Handled {
            action_id: "record".into(),
        },
    );
    let written = serde_json::to_value(&handled).unwrap();
    assert_eq!(
        written["kind"],
        json!({ "kind": "handled", "actionId": "record" })
    );
    assert_eq!(
        serde_json::from_value::<ActivityEvent>(written).unwrap(),
        handled
    );
    handled.validate().unwrap();

    for action_id in ["", "-record", "re cord", &"x".repeat(65)] {
        let wrong = ActivityEvent::new(
            1,
            "activity-id",
            1_000,
            EventKind::Handled {
                action_id: action_id.into(),
            },
        );
        assert_eq!(
            wrong.validate().unwrap_err().code,
            ErrorCode::InvalidInput,
            "{action_id:?}"
        );
    }
    let missing: Value =
        json!({ "sequence": 1, "activityId": "a", "occurredAt": 1, "kind": { "kind": "handled" } });
    assert!(serde_json::from_value::<ActivityEvent>(missing).is_err());

    // Drained beside the other kinds, in the order they were stored.
    let driver = FakeDriver::default();
    let batch = EventBatch {
        events: vec![
            ActivityEvent::new(
                1,
                "activity-id",
                1,
                EventKind::Action {
                    action_id: "track".into(),
                },
            ),
            handled,
        ],
        dropped: 0,
    };
    driver.edit(|state| state.events = batch.clone());
    assert_eq!(LiveUpdate::new(driver).events().unwrap(), batch);
}
