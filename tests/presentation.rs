//! Android rich-presentation schema, validation and compatibility tests.

use liveupdate::{
    AndroidPresentation, BigTextStyle, ErrorCode, Fallback, IconSource, MAX_ACTIONS, MAX_METRICS,
    MAX_PROGRESS_ELEMENTS, Metric, MetricStyle, MetricValue, NotificationAction, PresentationStyle,
    ProgressPoint, ProgressSegment, ProgressStyle, SamsungNowBar, SemanticStyle, Snapshot,
    StartRequest, Template, Timer, TimerStyle, Visibility,
};
use serde_json::{Value, json};

fn legacy_snapshot() -> Snapshot {
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

fn presentation(style: PresentationStyle) -> AndroidPresentation {
    AndroidPresentation {
        style,
        short_critical_text: Some("25%".into()),
        large_icon: Some(IconSource::Resource("delivery_large".into())),
        actions: vec![NotificationAction {
            id: "cancel".into(),
            title: "Cancel".into(),
            icon: Some(IconSource::Resource("cancel_action".into())),
            authentication_required: false,
        }],
        samsung_now_bar: Some(SamsungNowBar {
            primary: Some("Order".into()),
            secondary: Some("Preparing".into()),
        }),
    }
}

fn v2_snapshot(style: PresentationStyle) -> Snapshot {
    Snapshot {
        progress: None,
        timer: None,
        presentation: Some(presentation(style)),
        ..legacy_snapshot()
    }
}

fn v2_start(template: Template, style: PresentationStyle) -> StartRequest {
    StartRequest {
        activity_key: "delivery:42".into(),
        schema_version: 2,
        template,
        fallback: Fallback::Notification,
        visibility: Visibility::Private,
        snapshot: v2_snapshot(style),
    }
}

fn valid_progress() -> ProgressStyle {
    ProgressStyle {
        progress: 60,
        indeterminate: false,
        styled_by_progress: true,
        segments: vec![
            ProgressSegment {
                length: 50,
                id: Some(1),
                color: Some(0xff00ff00),
                semantic_style: Some(SemanticStyle::Safe),
            },
            ProgressSegment {
                length: 50,
                id: Some(2),
                color: None,
                semantic_style: Some(SemanticStyle::Caution),
            },
        ],
        points: vec![ProgressPoint {
            position: 50,
            id: Some(10),
            color: Some(0xffff0000),
            semantic_style: Some(SemanticStyle::Danger),
        }],
        start_icon: Some(IconSource::Resource("shop".into())),
        end_icon: Some(IconSource::Resource("home".into())),
        tracker_icon: Some(IconSource::AppFile("liveupdate/truck.png".into())),
    }
}

fn assert_invalid(result: liveupdate::Result<()>) {
    assert_eq!(result.unwrap_err().code, ErrorCode::InvalidInput);
}

// LU-V2-COMPAT-1
#[test]
fn schema_one_keeps_its_exact_wire_shape() {
    let raw = json!({
        "activityKey": "delivery:42",
        "schemaVersion": 1,
        "template": "progress",
        "fallback": "notification",
        "visibility": "private",
        "snapshot": {
            "revision": 0,
            "title": "Delivery",
            "status": "Preparing",
            "progress": 0.25
        }
    });
    let request: StartRequest = serde_json::from_value(raw.clone()).unwrap();
    request.validate().unwrap();
    assert_eq!(serde_json::to_value(request).unwrap(), raw);
}

// LU-V2-MODEL-1, LU-V2-COMPAT-1
#[test]
fn schema_versions_cannot_mix_legacy_and_rich_presentations() {
    let legacy = StartRequest {
        activity_key: "delivery:42".into(),
        schema_version: 1,
        template: Template::Progress,
        fallback: Fallback::Notification,
        visibility: Visibility::Private,
        snapshot: legacy_snapshot(),
    };
    legacy.validate().unwrap();

    let mut rich_under_v1 = v2_start(
        Template::Progress,
        PresentationStyle::Progress(valid_progress()),
    );
    rich_under_v1.schema_version = 1;
    assert_invalid(rich_under_v1.validate());

    let mut legacy_under_v2 = legacy.clone();
    legacy_under_v2.schema_version = 2;
    assert_invalid(legacy_under_v2.validate());

    let mut mixed = v2_start(
        Template::Progress,
        PresentationStyle::Progress(valid_progress()),
    );
    mixed.snapshot.progress = Some(0.5);
    assert_invalid(mixed.validate());
}

// LU-V2-MODEL-2
#[test]
fn every_system_style_has_a_stable_tagged_shape_and_template() {
    let styles = [
        (
            Template::Standard,
            PresentationStyle::Standard,
            json!({ "kind": "standard" }),
        ),
        (
            Template::BigText,
            PresentationStyle::BigText(BigTextStyle {
                text: "A longer explanation".into(),
                summary: Some("Summary".into()),
            }),
            json!({
                "kind": "bigText",
                "value": { "text": "A longer explanation", "summary": "Summary" }
            }),
        ),
        (
            Template::Progress,
            PresentationStyle::Progress(valid_progress()),
            serde_json::to_value(PresentationStyle::Progress(valid_progress())).unwrap(),
        ),
        (
            Template::Timer,
            PresentationStyle::Timer(TimerStyle {
                timer: Timer {
                    started_at: 1_000,
                    ends_at: 2_000,
                },
                counts_down: false,
            }),
            json!({
                "kind": "timer",
                "value": {
                    "timer": { "startedAt": 1000, "endsAt": 2000 },
                    "countsDown": false
                }
            }),
        ),
        (
            Template::Metric,
            PresentationStyle::Metric(MetricStyle {
                metrics: vec![Metric {
                    label: "Steps".into(),
                    value: MetricValue::Integer {
                        value: 1_979,
                        unit: None,
                    },
                    semantic_style: Some(SemanticStyle::Info),
                }],
                critical_metric: Some(0),
            }),
            json!({
                "kind": "metric",
                "value": {
                    "metrics": [{
                        "label": "Steps",
                        "value": { "kind": "integer", "value": { "value": 1979 } },
                        "semanticStyle": "info"
                    }],
                    "criticalMetric": 0
                }
            }),
        ),
    ];

    for (template, style, style_json) in styles {
        assert_eq!(serde_json::to_value(&style).unwrap(), style_json);
        let request = v2_start(template, style);
        request.validate().unwrap();
        assert_eq!(request.snapshot.template().unwrap(), template);
    }
}

// LU-V2-PROGRESS-1
#[test]
fn rich_progress_accepts_the_complete_shape_and_rejects_each_structural_boundary() {
    let style = valid_progress();
    style.validate().unwrap();

    let mut invalid = style.clone();
    invalid.progress = 101;
    assert_invalid(invalid.validate());

    let mut invalid = style.clone();
    invalid.segments[0].length = 0;
    assert_invalid(invalid.validate());

    let mut invalid = style.clone();
    invalid.points[0].position = 101;
    assert_invalid(invalid.validate());

    let mut invalid = style.clone();
    invalid.points.push(ProgressPoint {
        position: 50,
        id: Some(11),
        color: None,
        semantic_style: None,
    });
    assert_invalid(invalid.validate());

    let mut invalid = style.clone();
    invalid.segments[1].id = Some(1);
    assert_invalid(invalid.validate());

    let mut invalid = style.clone();
    invalid.segments = (0..=MAX_PROGRESS_ELEMENTS)
        .map(|_| ProgressSegment {
            length: 1,
            id: None,
            color: None,
            semantic_style: None,
        })
        .collect();
    assert_invalid(invalid.validate());

    let mut indeterminate = style;
    indeterminate.indeterminate = true;
    indeterminate.progress = 0;
    indeterminate.points.clear();
    indeterminate.validate().unwrap();
}

// LU-V2-ACTION-1, LU-V2-ICON-1
#[test]
fn actions_and_icon_sources_are_closed_unique_and_bounded() {
    let mut model = presentation(PresentationStyle::Standard);
    model.validate().unwrap();

    for bad in ["", "-cancel", "cancel now", "취소", &"x".repeat(65)] {
        let mut invalid = model.clone();
        invalid.actions[0].id = bad.into();
        assert_invalid(invalid.validate());
    }

    let mut duplicate = model.clone();
    duplicate.actions.push(duplicate.actions[0].clone());
    assert_invalid(duplicate.validate());

    let action = model.actions[0].clone();
    model.actions = (0..=MAX_ACTIONS)
        .map(|index| NotificationAction {
            id: format!("action{index}"),
            ..action.clone()
        })
        .collect();
    assert_invalid(model.validate());

    for source in [
        IconSource::Resource("Bad-Resource".into()),
        IconSource::Resource("9starts_wrong".into()),
        IconSource::AppFile("/absolute.png".into()),
        IconSource::AppFile("../escape.png".into()),
        IconSource::AppFile("liveupdate/../../escape.png".into()),
        IconSource::AppFile("liveupdate\\escape.png".into()),
    ] {
        assert_invalid(source.validate());
    }
    IconSource::Resource("delivery_2".into())
        .validate()
        .unwrap();
    IconSource::AppFile("liveupdate/images/truck.png".into())
        .validate()
        .unwrap();
}

// LU-V2-METRIC-1
#[test]
fn metrics_are_finite_labeled_and_have_a_valid_critical_index() {
    let values = vec![
        MetricValue::Integer {
            value: -12,
            unit: Some("km".into()),
        },
        MetricValue::Decimal {
            value: 12.5,
            unit: Some("km".into()),
        },
        MetricValue::Text("Ready".into()),
        MetricValue::Stopwatch { started_at: 1_000 },
        MetricValue::Countdown { ends_at: 2_000 },
    ];
    for value in values {
        Metric {
            label: "Distance".into(),
            value,
            semantic_style: None,
        }
        .validate()
        .unwrap();
    }

    assert_invalid(
        Metric {
            label: "Distance".into(),
            value: MetricValue::Decimal {
                value: f64::NAN,
                unit: None,
            },
            semantic_style: None,
        }
        .validate(),
    );

    let metric = Metric {
        label: "Steps".into(),
        value: MetricValue::Integer {
            value: 42,
            unit: None,
        },
        semantic_style: None,
    };
    for count in [0, MAX_METRICS + 1] {
        let style = MetricStyle {
            metrics: vec![metric.clone(); count],
            critical_metric: None,
        };
        assert_invalid(style.validate());
    }
    assert_invalid(
        MetricStyle {
            metrics: vec![metric],
            critical_metric: Some(1),
        }
        .validate(),
    );
}

// LU-V2-VALID-1
#[test]
fn nested_models_reject_unknown_fields_and_enforce_text_limits() {
    let request = v2_start(
        Template::BigText,
        PresentationStyle::BigText(BigTextStyle {
            text: "설명".repeat(1_000),
            summary: Some("요약".into()),
        }),
    );
    request.validate().unwrap();

    let mut too_long = request.clone();
    let Some(AndroidPresentation {
        style: PresentationStyle::BigText(style),
        ..
    }) = too_long.snapshot.presentation.as_mut()
    else {
        unreachable!()
    };
    style.text = "x".repeat(4_001);
    assert_invalid(too_long.validate());

    let mut empty_chip = request.clone();
    empty_chip
        .snapshot
        .presentation
        .as_mut()
        .unwrap()
        .short_critical_text = Some(String::new());
    assert_invalid(empty_chip.validate());

    let mut chip = request;
    chip.snapshot
        .presentation
        .as_mut()
        .unwrap()
        .short_critical_text = Some("12345678".into());
    assert_invalid(chip.validate());

    let mut value =
        serde_json::to_value(v2_start(Template::Standard, PresentationStyle::Standard)).unwrap();
    value["snapshot"]["presentation"]["extra"] = Value::Bool(true);
    assert!(serde_json::from_value::<StartRequest>(value).is_err());
}
