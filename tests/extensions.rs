//! The optional parts of the schema-2 contract: links, style changes, the delayed end, the
//! foreground placement, alerts, the public version and branding.

mod common;

use common::{FakeDriver, assert_code, request, snapshot};
use liveupdate::{
    ActivityEvent, ActivityState, AndroidPresentation, BigTextStyle, EndPolicy, ErrorCode,
    EventBatch, EventKind, Fallback, IconSource, LiveUpdate, NotificationAction, Placement,
    PresentationStyle, ProgressSegment, ProgressStyle, PublicVersion, Snapshot, StartRequest,
    Template, Visibility,
};
use serde_json::{Value, json};

fn progress(value: u32) -> PresentationStyle {
    PresentationStyle::Progress(ProgressStyle {
        progress: value,
        indeterminate: false,
        styled_by_progress: false,
        segments: vec![ProgressSegment {
            length: 100,
            id: None,
            color: None,
            semantic_style: None,
        }],
        points: Vec::new(),
        start_icon: None,
        end_icon: None,
        tracker_icon: None,
    })
}

fn rich(revision: u64, presentation: AndroidPresentation) -> Snapshot {
    Snapshot {
        revision,
        title: "Delivery".into(),
        status: "Preparing".into(),
        progress: None,
        timer: None,
        stale_at: None,
        presentation: Some(presentation),
    }
}

fn styled(revision: u64, style: PresentationStyle) -> Snapshot {
    rich(revision, AndroidPresentation::new(style))
}

fn rich_request(key: &str, snapshot: Snapshot) -> StartRequest {
    StartRequest {
        activity_key: key.into(),
        schema_version: 2,
        template: snapshot.template().unwrap(),
        fallback: Fallback::Notification,
        visibility: Visibility::Private,
        snapshot,
        placement: Placement::Tagged,
    }
}

fn assert_invalid(result: liveupdate::Result<()>) {
    assert_eq!(result.unwrap_err().code, ErrorCode::InvalidInput);
}

fn with_url(url: &str) -> AndroidPresentation {
    AndroidPresentation {
        open_url: Some(url.into()),
        ..AndroidPresentation::new(PresentationStyle::Standard)
    }
}

fn with_action_url(url: &str) -> AndroidPresentation {
    AndroidPresentation {
        actions: vec![NotificationAction {
            open_url: Some(url.into()),
            ..NotificationAction::new("track", "Track")
        }],
        ..AndroidPresentation::new(PresentationStyle::Standard)
    }
}

// LU-V2-LINK-1
#[test]
fn a_link_is_an_absolute_printable_ascii_url_with_an_allowed_lowercase_scheme() {
    let longest = format!("a:{}", "x".repeat(2_046));
    for url in [
        "myapp://orders/42",
        "https://example.com/orders/42?tab=map#eta",
        "a1+b.c-d:x",
        "app:!~",
        longest.as_str(),
    ] {
        with_url(url).validate().unwrap();
        with_action_url(url).validate().unwrap();
    }

    let too_long = format!("a:{}", "x".repeat(2_047));
    for url in [
        "",
        "orders/42",
        ":orders",
        "myapp:",
        "MyApp://orders",
        "myApp://orders",
        "1app://orders",
        "my_app://orders",
        "myapp://orders 42",
        "myapp://orders\t42",
        "myapp://orders\u{7f}",
        "myapp://주문",
        "intent://orders#Intent;end",
        "content://provider/1",
        "file:///data/x",
        "javascript:alert(1)",
        "data:text/plain,x",
        "android-app://package/x",
        too_long.as_str(),
    ] {
        assert_invalid(with_url(url).validate());
        assert_invalid(with_action_url(url).validate());
    }
}

// LU-V2-LINK-1, LU-V2-COMPAT-2
#[test]
fn links_have_a_stable_wire_shape_and_are_left_out_when_unset() {
    let opening = AndroidPresentation {
        open_url: Some("myapp://orders/42".into()),
        actions: vec![
            NotificationAction {
                open_url: Some("myapp://orders/42/map".into()),
                ..NotificationAction::new("track", "Track")
            },
            NotificationAction::new("cancel", "Cancel"),
        ],
        ..AndroidPresentation::new(PresentationStyle::Standard)
    };
    let wire = serde_json::to_value(&opening).unwrap();
    assert_eq!(
        wire,
        json!({
            "style": { "kind": "standard" },
            "actions": [
                { "id": "track", "title": "Track", "openUrl": "myapp://orders/42/map" },
                { "id": "cancel", "title": "Cancel" }
            ],
            "openUrl": "myapp://orders/42"
        })
    );
    assert_eq!(
        serde_json::from_value::<AndroidPresentation>(wire).unwrap(),
        opening
    );
}

// LU-V2-EVENT-7
#[test]
fn an_opened_event_names_its_action_only_when_one_opened_the_application() {
    let event = |sequence, kind| ActivityEvent {
        sequence,
        activity_id: "activity-id".into(),
        occurred_at: 1_700_000_000_000,
        kind,
    };
    let batch = EventBatch {
        events: vec![
            event(1, EventKind::Opened { action_id: None }),
            event(
                2,
                EventKind::Opened {
                    action_id: Some("track".into()),
                },
            ),
        ],
        dropped: 0,
    };
    let wire = serde_json::to_value(&batch).unwrap();
    assert_eq!(wire["events"][0]["kind"], json!({ "kind": "opened" }));
    assert_eq!(
        wire["events"][1]["kind"],
        json!({ "kind": "opened", "actionId": "track" })
    );
    assert_eq!(
        serde_json::from_value::<EventBatch>(wire.clone()).unwrap(),
        batch
    );
    batch.validate().unwrap();

    let invalid = event(
        1,
        EventKind::Opened {
            action_id: Some("not valid".into()),
        },
    );
    assert_invalid(invalid.validate());
    let mut unknown_member = wire;
    unknown_member["events"][0]["kind"]["url"] = Value::String("myapp://x".into());
    assert!(serde_json::from_value::<EventBatch>(unknown_member).is_err());

    let driver = FakeDriver::default();
    driver.edit(|state| state.events = batch.clone());
    assert_eq!(LiveUpdate::new(driver).events().unwrap(), batch);
}

// LU-V2-ALERT-1
#[test]
fn an_alert_is_a_flag_of_one_snapshot_and_is_not_carried_to_the_next() {
    let alerting = AndroidPresentation {
        alert: true,
        ..AndroidPresentation::new(PresentationStyle::Standard)
    };
    alerting.validate().unwrap();
    assert_eq!(
        serde_json::to_value(&alerting).unwrap(),
        json!({ "style": { "kind": "standard" }, "alert": true })
    );
    let quiet: AndroidPresentation =
        serde_json::from_value(json!({ "style": { "kind": "standard" } })).unwrap();
    assert!(!quiet.alert);
    assert!(
        serde_json::from_value::<AndroidPresentation>(
            json!({ "style": { "kind": "standard" }, "alert": 1 })
        )
        .is_err()
    );

    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let started = client
        .start(rich_request(
            "delivery:1",
            styled(0, PresentationStyle::Standard),
        ))
        .unwrap();
    let alerted = client.update(&started.id, rich(1, alerting)).unwrap();
    assert!(alerted.snapshot.presentation.unwrap().alert);
    let after = client
        .update(&started.id, styled(2, PresentationStyle::Standard))
        .unwrap();
    assert!(!after.snapshot.presentation.unwrap().alert);
    assert_eq!(driver.counts().0, 3);
}

fn with_public(title: &str, status: &str) -> AndroidPresentation {
    AndroidPresentation {
        public_version: Some(PublicVersion {
            title: title.into(),
            status: status.into(),
        }),
        ..AndroidPresentation::new(PresentationStyle::Standard)
    }
}

// LU-V2-PUBLIC-1
#[test]
fn a_public_version_is_bounded_and_needs_a_private_activity() {
    let public = with_public(&"t".repeat(120), &"s".repeat(240));
    public.validate().unwrap();
    assert_eq!(
        serde_json::to_value(with_public("Delivery", "In progress")).unwrap(),
        json!({
            "style": { "kind": "standard" },
            "publicVersion": { "title": "Delivery", "status": "In progress" }
        })
    );
    for (title, status) in [
        ("", "In progress"),
        ("Delivery", ""),
        (" ", "In progress"),
        (&*"t".repeat(121), "In progress"),
        ("Delivery", &*"s".repeat(241)),
    ] {
        assert_invalid(with_public(title, status).validate());
    }
    assert!(
        serde_json::from_value::<AndroidPresentation>(json!({
            "style": { "kind": "standard" },
            "publicVersion": { "title": "Delivery", "status": "In progress", "icon": "x" }
        }))
        .is_err()
    );

    let private = rich_request(
        "delivery:1",
        rich(0, with_public("Delivery", "In progress")),
    );
    private.validate().unwrap();
    for visibility in [Visibility::Public, Visibility::Secret] {
        assert_invalid(
            StartRequest {
                visibility,
                ..private.clone()
            }
            .validate(),
        );
    }
}

// LU-V2-PUBLIC-1
#[test]
fn an_update_cannot_give_a_public_version_to_an_activity_that_is_not_private() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let public_activity = client
        .start(StartRequest {
            visibility: Visibility::Public,
            ..rich_request("delivery:1", styled(0, PresentationStyle::Standard))
        })
        .unwrap();
    assert_code(
        client.update(
            &public_activity.id,
            rich(1, with_public("Delivery", "In progress")),
        ),
        ErrorCode::InvalidInput,
    );
    assert_eq!(driver.counts().0, 1);

    let private_activity = client
        .start(rich_request(
            "delivery:2",
            styled(0, PresentationStyle::Standard),
        ))
        .unwrap();
    client
        .update(
            &private_activity.id,
            rich(1, with_public("Delivery", "In progress")),
        )
        .unwrap();
}

// LU-V2-BRAND-1
#[test]
fn branding_takes_a_resource_icon_a_color_and_a_short_text() {
    let branded = AndroidPresentation {
        small_icon: Some(IconSource::Resource("ic_delivery".into())),
        accent_color: Some(0xff21_96f3),
        sub_text: Some("x".repeat(64)),
        ..AndroidPresentation::new(PresentationStyle::Standard)
    };
    branded.validate().unwrap();
    let wire = serde_json::to_value(&branded).unwrap();
    assert_eq!(
        wire["smallIcon"],
        json!({ "kind": "resource", "value": "ic_delivery" })
    );
    assert_eq!(wire["accentColor"], json!(0xff21_96f3_u32));
    assert_eq!(
        serde_json::from_value::<AndroidPresentation>(wire).unwrap(),
        branded
    );

    for invalid in [
        AndroidPresentation {
            small_icon: Some(IconSource::AppFile("icons/delivery.png".into())),
            ..branded.clone()
        },
        AndroidPresentation {
            small_icon: Some(IconSource::Resource("Ic-Delivery".into())),
            ..branded.clone()
        },
        AndroidPresentation {
            sub_text: Some("x".repeat(65)),
            ..branded.clone()
        },
        AndroidPresentation {
            sub_text: Some(String::new()),
            ..branded.clone()
        },
    ] {
        assert_invalid(invalid.validate());
    }
    assert!(
        serde_json::from_value::<AndroidPresentation>(
            json!({ "style": { "kind": "standard" }, "accentColor": 4_294_967_296_u64 })
        )
        .is_err()
    );
}

// LU-FGS-1
#[test]
fn a_placement_is_part_of_a_schema_two_start_and_left_out_when_tagged() {
    let tagged = rich_request("delivery:1", styled(0, PresentationStyle::Standard));
    let wire = serde_json::to_value(&tagged).unwrap();
    assert!(wire.get("placement").is_none());
    assert_eq!(
        serde_json::from_value::<StartRequest>(wire.clone()).unwrap(),
        tagged
    );

    let foreground = StartRequest {
        placement: Placement::Foreground,
        ..tagged
    };
    foreground.validate().unwrap();
    let mut expected = wire;
    expected["placement"] = json!("foreground");
    assert_eq!(serde_json::to_value(&foreground).unwrap(), expected);
    assert_eq!(
        serde_json::from_value::<StartRequest>(expected.clone()).unwrap(),
        foreground
    );
    expected["placement"] = json!("floating");
    assert!(serde_json::from_value::<StartRequest>(expected).is_err());

    assert_invalid(
        StartRequest {
            placement: Placement::Foreground,
            ..request("delivery:1")
        }
        .validate(),
    );
    assert!(
        serde_json::to_value(request("delivery:1"))
            .unwrap()
            .get("placement")
            .is_none()
    );
}

fn foreground_request(key: &str) -> StartRequest {
    StartRequest {
        placement: Placement::Foreground,
        ..rich_request(key, styled(0, PresentationStyle::Standard))
    }
}

// LU-FGS-2
#[test]
fn the_foreground_placement_needs_its_capability_and_one_live_activity_has_it() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    assert_code(
        client.start(foreground_request("ride:1")),
        ErrorCode::UnsupportedFeature,
    );
    assert_eq!(driver.counts().0, 0);
    assert!(driver.records().is_empty());

    driver.edit(|state| state.capabilities.presentation.foreground_placement = true);
    let held = client.start(foreground_request("ride:1")).unwrap();
    assert_eq!(driver.records()[0].origin.placement, Placement::Foreground);
    assert_eq!(
        serde_json::to_value(&driver.records()[0]).unwrap()["origin"]["placement"],
        json!("foreground")
    );

    // The same start answers the running activity; another placement under its key is another request.
    assert_eq!(
        client.start(foreground_request("ride:1")).unwrap().id,
        held.id
    );
    assert_code(
        client.start(rich_request(
            "ride:1",
            styled(0, PresentationStyle::Standard),
        )),
        ErrorCode::Conflict,
    );
    assert_code(
        client.start(foreground_request("ride:2")),
        ErrorCode::SystemLimit,
    );
    assert_eq!(driver.counts().0, 1);

    // A tagged activity is not limited by it, and the placement is free again once its holder ended.
    client
        .start(rich_request(
            "delivery:1",
            styled(0, PresentationStyle::Standard),
        ))
        .unwrap();
    client.end(&held.id, EndPolicy::Immediate).unwrap();
    let next = client.start(foreground_request("ride:2")).unwrap();
    assert_ne!(next.id, held.id);

    // A holder whose last content still stays, or whose notification the user removed, is not live.
    driver.edit(|state| state.capabilities.presentation.delayed_end = true);
    client
        .end(&next.id, EndPolicy::After { at: 5_000 })
        .unwrap();
    let third = client.start(foreground_request("ride:3")).unwrap();
    driver.dismiss(&third.id);
    client.start(foreground_request("ride:4")).unwrap();
}

// LU-V2-MODEL-3, LU-UPDATE-5
#[test]
fn a_schema_two_update_may_change_the_style_and_the_record_follows_it() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let origin = rich_request("delivery:1", styled(0, progress(10)));
    let started = client.start(origin.clone()).unwrap();
    assert_eq!(started.template, Template::Progress);

    let arrived = styled(
        1,
        PresentationStyle::BigText(BigTextStyle {
            text: "Left at the door".into(),
            summary: None,
        }),
    );
    let updated = client.update(&started.id, arrived.clone()).unwrap();
    assert_eq!(updated.template, Template::BigText);
    assert_eq!(updated.snapshot, arrived);
    assert_eq!(updated.state, ActivityState::Active);
    assert_eq!(client.list().unwrap()[0].template, Template::BigText);

    // The request of the start stays the origin: the same start answers the running activity.
    assert_eq!(driver.records()[0].origin, origin);
    let again = client.start(origin).unwrap();
    assert_eq!(again.id, started.id);
    assert_eq!(again.snapshot, arrived);
    assert_eq!(driver.counts().0, 2);

    // The generations do not mix.
    assert_code(
        client.update(&started.id, snapshot(2)),
        ErrorCode::InvalidInput,
    );
    let legacy = client.start(request("delivery:2")).unwrap();
    assert_code(
        client.update(&legacy.id, styled(1, progress(20))),
        ErrorCode::InvalidInput,
    );
    assert_eq!(driver.counts().0, 3);
}

// LU-V2-MODEL-3
#[test]
fn a_notification_that_still_shows_the_previous_style_is_awaited() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    let started = client
        .start(rich_request("delivery:1", styled(0, progress(10))))
        .unwrap();

    driver.edit(|state| state.delay_observation = true);
    let awaited = client
        .update(&started.id, styled(1, PresentationStyle::Standard))
        .unwrap();
    assert_eq!(awaited.state, ActivityState::Unknown);
    assert_eq!(awaited.template, Template::Standard);
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Unknown);
}

fn delaying_driver() -> FakeDriver {
    let driver = FakeDriver::default();
    driver.edit(|state| state.capabilities.presentation.delayed_end = true);
    driver
}

// LU-END-7
#[test]
fn a_delayed_end_posts_the_last_content_in_its_final_form() {
    let driver = delaying_driver();
    let client = LiveUpdate::new(driver.clone());
    let started = client.start(request("delivery:1")).unwrap();
    assert!(
        serde_json::to_value(&driver.records()[0])
            .unwrap()
            .get("dismissAt")
            .is_none()
    );
    driver.take_calls();

    let ended = client
        .end(&started.id, EndPolicy::After { at: 5_000 })
        .unwrap();

    assert_eq!(ended.state, ActivityState::Ended);
    assert_eq!(
        driver.take_calls(),
        ["read", "observe", "capabilities", "write", "post"]
    );
    let stored = driver.records();
    assert_eq!(stored[0].state, ActivityState::Ended);
    assert_eq!(stored[0].dismiss_at, Some(5_000));
    assert!(!stored[0].awaiting_observation);
    assert!(!stored[0].post_failed);
    assert_eq!(
        serde_json::to_value(&stored[0]).unwrap()["dismissAt"],
        json!(5_000)
    );
    let posted = driver.edit(|state| state.finals[&started.id].clone());
    assert_eq!(posted, stored[0]);
    assert_eq!(driver.counts(), (2, 0));

    // The final form is not observed, so nothing cancels it, and a second delayed end does nothing,
    // also once the adapter no longer offers it.
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Ended);
    driver.edit(|state| state.capabilities.presentation.delayed_end = false);
    driver.take_calls();
    let again = client
        .end(&started.id, EndPolicy::After { at: 9_000 })
        .unwrap();
    assert_eq!(again.state, ActivityState::Ended);
    assert_eq!(driver.take_calls(), ["read", "observe"]);
    assert_eq!(driver.counts(), (2, 0));
    assert_eq!(driver.records()[0].dismiss_at, Some(5_000));

    // An end without a time removes the last content early, once.
    let removed = client.end(&started.id, EndPolicy::Immediate).unwrap();
    assert_eq!(removed.state, ActivityState::Ended);
    assert_eq!(driver.take_calls(), ["read", "observe", "write", "cancel"]);
    assert_eq!(driver.records()[0].dismiss_at, None);
    client.end(&started.id, EndPolicy::Default).unwrap();
    assert_eq!(driver.counts(), (2, 1));
}

// LU-END-8
#[test]
fn a_delayed_end_does_not_post_what_the_user_removed_and_a_failed_final_post_is_cancelled() {
    let driver = delaying_driver();
    let client = LiveUpdate::new(driver.clone());
    let removed = client.start(request("delivery:1")).unwrap();
    driver.dismiss(&removed.id);
    assert_eq!(client.list().unwrap()[0].state, ActivityState::Dismissed);

    let ended = client
        .end(&removed.id, EndPolicy::After { at: 5_000 })
        .unwrap();
    assert_eq!(ended.state, ActivityState::Ended);
    assert_eq!(driver.records()[0].dismiss_at, None);
    assert_eq!(driver.counts(), (1, 1));
    assert!(driver.edit(|state| state.finals.is_empty()));

    let failing = client.start(request("delivery:2")).unwrap();
    driver.edit(|state| state.fail_next_post = true);
    assert_code(
        client.end(&failing.id, EndPolicy::After { at: 5_000 }),
        ErrorCode::NativeError,
    );
    let stored = driver
        .records()
        .into_iter()
        .find(|record| record.id == failing.id)
        .unwrap();
    assert_eq!(stored.state, ActivityState::Ended);
    // Android still shows the live notification, which the next reconciliation cancels.
    assert!(driver.edit(|state| state.observed.contains_key(&failing.id)));
    client.list().unwrap();
    assert!(driver.edit(|state| !state.observed.contains_key(&failing.id)));
    assert_eq!(driver.counts().1, 2);
}

// LU-LIST-10
#[test]
fn a_receipt_of_the_other_generation_from_an_earlier_release_is_read_and_replaced() {
    for (stored, fitting) in [
        // A schema-1 activity whose stored snapshot is a schema-2 one of its template.
        (
            (request("delivery:1"), styled(1, progress(10))),
            snapshot(2),
        ),
        // A schema-2 activity whose stored snapshot is a schema-1 one.
        (
            (
                rich_request("delivery:1", styled(0, progress(10))),
                snapshot(1),
            ),
            styled(2, PresentationStyle::Standard),
        ),
    ] {
        let (origin, mixed) = stored;
        let driver = FakeDriver::default();
        let client = LiveUpdate::new(driver.clone());
        let started = client.start(origin).unwrap();
        // What the earlier release wrote before the adapter refused the post.
        driver.edit(|state| {
            state.records[0].snapshot = mixed.clone();
            state.records[0].post_failed = true;
            state.records[0].awaiting_observation = true;
        });

        let listed = client.list().unwrap();
        assert_eq!(listed[0].snapshot, mixed);
        assert_code(
            client.update(&started.id, mixed.clone()),
            ErrorCode::InvalidInput,
        );
        let healed = client.update(&started.id, fitting.clone()).unwrap();
        assert_eq!(healed.snapshot, fitting);
        assert_eq!(healed.state, ActivityState::Active);
    }

    // A schema-1 receipt of another template was never written, and is refused as before.
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());
    client.start(request("delivery:1")).unwrap();
    driver.edit(|state| state.records[0].snapshot = common::timer_snapshot(1));
    assert_code(client.list(), ErrorCode::NativeError);
}
