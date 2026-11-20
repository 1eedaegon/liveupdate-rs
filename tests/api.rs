//! What keeps code written against this version compiling against the next: enums that may gain a
//! variant, and constructors for what a caller builds.
//!
//! `unreachable_patterns` is denied for the whole file: a `_` arm after every variant of an enum
//! then compiles only when the enum may gain a variant.
#![deny(unreachable_patterns)]

use liveupdate::{
    ActionTarget, ActivityEvent, ActivityState, AndroidPresentation, BigTextStyle, Capabilities,
    EndPolicy, ErrorCode, EventBatch, EventKind, Fallback, IconSource, Metric, MetricStyle,
    MetricValue, NextCheck, Observation, Placement, PresentationCapabilities, PresentationStyle,
    ProgressPoint, ProgressSegment, ProgressStyle, PromotionState, PublicVersion, SamsungNowBar,
    SemanticStyle, Snapshot, StartOutcome, StartReport, StartRequest, TargetKind, Template, Timer,
    TimerStyle, Visibility,
};
use serde_json::json;

// LU-COMPAT-4
#[test]
fn every_public_enum_may_gain_a_variant() {
    fn error_code(value: ErrorCode) -> u8 {
        match value {
            ErrorCode::InvalidInput
            | ErrorCode::Conflict
            | ErrorCode::NotFound
            | ErrorCode::StaleRevision
            | ErrorCode::PermissionDenied
            | ErrorCode::UnsupportedFeature
            | ErrorCode::SystemLimit
            | ErrorCode::NativeError => 1,
            _ => 0,
        }
    }
    fn event_kind(value: &EventKind) -> u8 {
        match value {
            EventKind::Action { .. }
            | EventKind::Dismissed
            | EventKind::Opened { .. }
            | EventKind::Handled { .. }
            | EventKind::Unknown => 1,
            _ => 0,
        }
    }
    fn activity_state(value: ActivityState) -> u8 {
        match value {
            ActivityState::Unknown
            | ActivityState::Scheduled
            | ActivityState::Active
            | ActivityState::Stale
            | ActivityState::Ended
            | ActivityState::Dismissed => 1,
            _ => 0,
        }
    }
    fn start_outcome(value: StartOutcome) -> u8 {
        match value {
            StartOutcome::Posted | StartOutcome::Gone => 1,
            _ => 0,
        }
    }
    fn fallback(value: Fallback) -> u8 {
        match value {
            Fallback::None | Fallback::Notification => 1,
            _ => 0,
        }
    }
    fn visibility(value: Visibility) -> u8 {
        match value {
            Visibility::Unknown | Visibility::Public | Visibility::Private | Visibility::Secret => {
                1
            }
            _ => 0,
        }
    }
    fn promotion_state(value: PromotionState) -> u8 {
        match value {
            PromotionState::Promoted
            | PromotionState::NotPromoted
            | PromotionState::NotApplicable
            | PromotionState::Unknown => 1,
            _ => 0,
        }
    }
    fn semantic_style(value: SemanticStyle) -> u8 {
        match value {
            SemanticStyle::Unspecified
            | SemanticStyle::Info
            | SemanticStyle::Safe
            | SemanticStyle::Caution
            | SemanticStyle::Danger => 1,
            _ => 0,
        }
    }
    fn icon_source(value: &IconSource) -> u8 {
        match value {
            IconSource::Resource(_) | IconSource::AppFile(_) => 1,
            _ => 0,
        }
    }
    fn metric_value(value: &MetricValue) -> u8 {
        match value {
            MetricValue::Integer { .. }
            | MetricValue::Decimal { .. }
            | MetricValue::Text(_)
            | MetricValue::Stopwatch { .. }
            | MetricValue::Countdown { .. } => 1,
            _ => 0,
        }
    }
    fn presentation_style(value: &PresentationStyle) -> u8 {
        match value {
            PresentationStyle::Standard
            | PresentationStyle::BigText(_)
            | PresentationStyle::Progress(_)
            | PresentationStyle::Timer(_)
            | PresentationStyle::Metric(_) => 1,
            _ => 0,
        }
    }
    fn template(value: Template) -> u8 {
        match value {
            Template::Standard
            | Template::BigText
            | Template::Progress
            | Template::Timer
            | Template::Metric => 1,
            _ => 0,
        }
    }
    fn placement(value: Placement) -> u8 {
        match value {
            Placement::Tagged | Placement::Foreground => 1,
            _ => 0,
        }
    }
    fn end_policy(value: EndPolicy) -> u8 {
        match value {
            EndPolicy::Immediate | EndPolicy::Default | EndPolicy::After { .. } => 1,
            _ => 0,
        }
    }
    fn target_kind(value: TargetKind) -> u8 {
        match value {
            TargetKind::Service | TargetKind::ForegroundService | TargetKind::Broadcast => 1,
            _ => 0,
        }
    }
    fn next_check(value: NextCheck) -> u8 {
        match value {
            NextCheck::Now | NextCheck::Soon | NextCheck::At(_) | NextCheck::Never => 1,
            _ => 0,
        }
    }

    assert_eq!(error_code(ErrorCode::Conflict), 1);
    assert_eq!(event_kind(&EventKind::Unknown), 1);
    assert_eq!(activity_state(ActivityState::Active), 1);
    assert_eq!(start_outcome(StartOutcome::Gone), 1);
    assert_eq!(fallback(Fallback::None), 1);
    assert_eq!(visibility(Visibility::Secret), 1);
    assert_eq!(promotion_state(PromotionState::Unknown), 1);
    assert_eq!(semantic_style(SemanticStyle::Danger), 1);
    assert_eq!(icon_source(&IconSource::Resource("icon".into())), 1);
    assert_eq!(metric_value(&MetricValue::Text("text".into())), 1);
    assert_eq!(presentation_style(&PresentationStyle::Standard), 1);
    assert_eq!(template(Template::Metric), 1);
    assert_eq!(placement(Placement::Foreground), 1);
    assert_eq!(end_policy(EndPolicy::After { at: 1 }), 1);
    assert_eq!(next_check(NextCheck::At(1)), 1);
    assert_eq!(target_kind(TargetKind::ForegroundService), 1);
}

// LU-COMPAT-4
#[test]
fn no_public_enum_of_the_crate_is_closed() {
    let sources = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let mut enums = 0;
    for entry in std::fs::read_dir(sources).unwrap() {
        let path = entry.unwrap().path();
        if path.extension().is_none_or(|extension| extension != "rs") {
            continue;
        }
        let source = std::fs::read_to_string(&path).unwrap();
        let lines: Vec<&str> = source.lines().collect();
        for (index, line) in lines.iter().enumerate() {
            if !line.starts_with("pub enum ") {
                continue;
            }
            enums += 1;
            // The attributes and the documentation stand directly above the enum.
            let open = lines[..index]
                .iter()
                .rev()
                .take_while(|above| {
                    above.starts_with('#')
                        || above.starts_with("///")
                        || above.starts_with(')')
                        || above.starts_with("    ")
                })
                .any(|above| above.trim() == "#[non_exhaustive]");
            assert!(open, "{}: {line} is not #[non_exhaustive]", path.display());
        }
    }
    // The sixteen that `every_public_enum_may_gain_a_variant` matches.
    assert_eq!(enums, 16);
}

// LU-COMPAT-5
#[test]
fn a_constructor_takes_what_is_required_and_sets_nothing_else() {
    assert_eq!(
        Timer::new(1, 2),
        Timer {
            started_at: 1,
            ends_at: 2
        }
    );
    assert_eq!(
        Snapshot::new(3, "Title", "Status"),
        Snapshot {
            revision: 3,
            title: "Title".into(),
            status: "Status".into(),
            progress: None,
            timer: None,
            stale_at: None,
            presentation: None,
        }
    );
    assert_eq!(
        BigTextStyle::new("Text"),
        BigTextStyle {
            text: "Text".into(),
            summary: None
        }
    );
    assert_eq!(
        ProgressSegment::new(4),
        ProgressSegment {
            length: 4,
            id: None,
            color: None,
            semantic_style: None
        }
    );
    assert_eq!(
        ProgressPoint::new(5),
        ProgressPoint {
            position: 5,
            id: None,
            color: None,
            semantic_style: None
        }
    );
    assert_eq!(
        ProgressStyle::new(6, vec![ProgressSegment::new(7)]),
        ProgressStyle {
            progress: 6,
            indeterminate: false,
            styled_by_progress: true,
            segments: vec![ProgressSegment::new(7)],
            points: Vec::new(),
            start_icon: None,
            end_icon: None,
            tracker_icon: None,
        }
    );
    for counts_down in [false, true] {
        assert_eq!(
            TimerStyle::new(Timer::new(1, 2), counts_down),
            TimerStyle {
                timer: Timer::new(1, 2),
                counts_down
            }
        );
    }
    let value = MetricValue::Text("42".into());
    assert_eq!(
        Metric::new("Label", value.clone()),
        Metric {
            label: "Label".into(),
            value: value.clone(),
            semantic_style: None
        }
    );
    assert_eq!(
        MetricStyle::new(vec![Metric::new("Label", value.clone())]),
        MetricStyle {
            metrics: vec![Metric::new("Label", value)],
            critical_metric: None
        }
    );
    assert_eq!(
        ActionTarget::new(TargetKind::Broadcast, "a.B"),
        ActionTarget {
            kind: TargetKind::Broadcast,
            component: "a.B".into(),
            action: None
        }
    );
    assert_eq!(
        PublicVersion::new("Title", "Status"),
        PublicVersion {
            title: "Title".into(),
            status: "Status".into()
        }
    );
    assert_eq!(
        SamsungNowBar::default(),
        SamsungNowBar {
            primary: None,
            secondary: None
        }
    );
    assert_eq!(
        Capabilities::default(),
        Capabilities {
            supported: false,
            notifications_enabled: false,
            promotion_enabled: false,
            presentation: PresentationCapabilities::default(),
        }
    );
}

// LU-V2-PROGRESS-3
#[test]
fn a_progress_style_from_its_constructor_is_styled_by_its_progress() {
    // As Android's own default: without it every segment is shown in full, and a bar without a
    // tracker icon does not show where its progress is.
    let style = ProgressStyle::new(40, vec![ProgressSegment::new(100)]);
    assert!(style.styled_by_progress);
    let written = serde_json::to_value(&style).unwrap();
    assert_eq!(
        written,
        json!({ "progress": 40, "styledByProgress": true, "segments": [{ "length": 100 }] })
    );
    assert_eq!(
        serde_json::from_value::<ProgressStyle>(written).unwrap(),
        style
    );

    // The member's JSON is what it was: left out when false, and false when left out, so a request and
    // a receipt of an earlier version read as before.
    let plain = ProgressStyle {
        styled_by_progress: false,
        ..style
    };
    let written = serde_json::to_value(&plain).unwrap();
    assert_eq!(
        written,
        json!({ "progress": 40, "segments": [{ "length": 100 }] })
    );
    assert_eq!(
        serde_json::from_value::<ProgressStyle>(written).unwrap(),
        plain
    );
}

// LU-COMPAT-5
#[test]
fn what_a_driver_answers_has_constructors_too() {
    let snapshot = Snapshot::new(8, "Title", "Status");
    assert_eq!(
        Observation::new(
            "activity-id",
            snapshot.clone(),
            ActivityState::Stale,
            true,
            PromotionState::Promoted
        ),
        Observation {
            id: "activity-id".into(),
            snapshot,
            state: ActivityState::Stale,
            promotion_requested: true,
            promotion_state: PromotionState::Promoted,
        }
    );
    assert_eq!(
        StartReport::new("activity-id", StartOutcome::Gone),
        StartReport {
            id: "activity-id".into(),
            outcome: StartOutcome::Gone
        }
    );
    assert_eq!(
        ActivityEvent::new(9, "activity-id", 10, EventKind::Dismissed),
        ActivityEvent {
            sequence: 9,
            activity_id: "activity-id".into(),
            occurred_at: 10,
            kind: EventKind::Dismissed,
        }
    );
}

// LU-COMPAT-5
#[test]
fn a_start_request_takes_its_schema_version_and_its_template_from_its_snapshot() {
    let legacy = [
        (
            Snapshot {
                progress: Some(0.5),
                ..Snapshot::new(0, "Title", "Status")
            },
            Template::Progress,
        ),
        (
            Snapshot {
                timer: Some(Timer::new(1, 2)),
                ..Snapshot::new(0, "Title", "Status")
            },
            Template::Timer,
        ),
    ];
    for (snapshot, template) in legacy {
        let request = StartRequest::new(
            "delivery:1",
            Fallback::Notification,
            Visibility::Private,
            snapshot.clone(),
        );
        assert_eq!(
            request,
            StartRequest {
                activity_key: "delivery:1".into(),
                schema_version: 1,
                template,
                fallback: Fallback::Notification,
                visibility: Visibility::Private,
                snapshot,
                placement: Placement::Tagged,
                start_at: None,
            }
        );
        request.validate().unwrap();
    }

    let styles = [
        (PresentationStyle::Standard, Template::Standard),
        (
            PresentationStyle::BigText(BigTextStyle::new("Text")),
            Template::BigText,
        ),
        (
            PresentationStyle::Progress(ProgressStyle::new(1, vec![ProgressSegment::new(2)])),
            Template::Progress,
        ),
        (
            PresentationStyle::Timer(TimerStyle::new(Timer::new(1, 2), true)),
            Template::Timer,
        ),
        (
            PresentationStyle::Metric(MetricStyle::new(vec![Metric::new(
                "Label",
                MetricValue::Text("42".into()),
            )])),
            Template::Metric,
        ),
    ];
    for (style, template) in styles {
        let snapshot = Snapshot {
            presentation: Some(AndroidPresentation::new(style)),
            ..Snapshot::new(0, "Title", "Status")
        };
        let request = StartRequest::new("delivery:1", Fallback::None, Visibility::Secret, snapshot);
        assert_eq!(request.schema_version, 2);
        assert_eq!(request.template, template);
        assert_eq!(request.fallback, Fallback::None);
        assert_eq!(request.visibility, Visibility::Secret);
        request.validate().unwrap();
    }

    // A snapshot that shows nothing, or two things, is no request, whatever the constructor made of it.
    let empty = Snapshot::new(0, "Title", "Status");
    let both = Snapshot {
        progress: Some(0.5),
        presentation: Some(AndroidPresentation::new(PresentationStyle::Standard)),
        ..Snapshot::new(0, "Title", "Status")
    };
    for snapshot in [empty, both] {
        let request = StartRequest::new(
            "delivery:1",
            Fallback::Notification,
            Visibility::Private,
            snapshot,
        );
        assert_eq!(
            request.validate().unwrap_err().code,
            ErrorCode::InvalidInput
        );
    }
}

// LU-COMPAT-5
#[test]
fn what_the_constructors_build_is_written_as_the_smallest_json() {
    let request = StartRequest::new(
        "delivery:1",
        Fallback::Notification,
        Visibility::Private,
        Snapshot {
            presentation: Some(AndroidPresentation {
                samsung_now_bar: Some(SamsungNowBar::default()),
                public_version: Some(PublicVersion::new("Delivery", "On its way")),
                ..AndroidPresentation::new(PresentationStyle::Progress(ProgressStyle {
                    points: vec![ProgressPoint::new(1)],
                    ..ProgressStyle::new(1, vec![ProgressSegment::new(2)])
                }))
            }),
            ..Snapshot::new(0, "Title", "Status")
        },
    );
    assert_eq!(
        serde_json::to_value(&request).unwrap(),
        json!({
            "activityKey": "delivery:1",
            "schemaVersion": 2,
            "template": "progress",
            "fallback": "notification",
            "visibility": "private",
            "snapshot": {
                "revision": 0,
                "title": "Title",
                "status": "Status",
                "presentation": {
                    "style": {
                        "kind": "progress",
                        "value": {
                            "progress": 1,
                            "styledByProgress": true,
                            "segments": [{ "length": 2 }],
                            "points": [{ "position": 1 }]
                        }
                    },
                    "samsungNowBar": {},
                    "publicVersion": { "title": "Delivery", "status": "On its way" }
                }
            }
        })
    );
    assert_eq!(
        serde_json::to_value(EventBatch::default()).unwrap(),
        json!({ "events": [], "dropped": 0 })
    );
}
