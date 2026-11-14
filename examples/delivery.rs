//! One delivery from start to end. `run_delivery` works with any `Driver`, so tests can pass a fake
//! one; on Android, `on_android` hands it the client of the companion AAR.

use liveupdate::{
    AndroidPresentation, Driver, EndPolicy, Fallback, IconSource, LiveUpdate, NotificationAction,
    Placement, PresentationStyle, ProgressPoint, ProgressSegment, ProgressStyle, Result,
    SamsungNowBar, SemanticStyle, Snapshot, StartRequest, Template, Visibility,
};

fn delivery_progress(progress: u32, icon: &IconSource) -> ProgressStyle {
    ProgressStyle {
        progress,
        indeterminate: false,
        styled_by_progress: true,
        segments: vec![
            ProgressSegment {
                length: 25,
                id: Some(1),
                color: Some(0xff21_96f3),
                semantic_style: Some(SemanticStyle::Info),
            },
            ProgressSegment {
                length: 75,
                id: Some(2),
                color: Some(0xff4c_af50),
                semantic_style: Some(SemanticStyle::Safe),
            },
        ],
        points: vec![ProgressPoint {
            position: 25,
            id: Some(10),
            color: Some(0xffff_a000),
            semantic_style: Some(SemanticStyle::Caution),
        }],
        start_icon: Some(icon.clone()),
        end_icon: Some(icon.clone()),
        tracker_icon: Some(icon.clone()),
    }
}

/// Schema-v2 content for Android's rich progress presentation.
fn delivery_snapshot(revision: u64, progress: u32, status: &str) -> Snapshot {
    let icon = IconSource::Resource("liveupdate_status".into());
    let style = PresentationStyle::Progress(delivery_progress(progress, &icon));
    Snapshot {
        revision,
        title: "Mochi's snack delivery".into(),
        status: status.into(),
        progress: None,
        timer: None,
        stale_at: None,
        presentation: Some(AndroidPresentation {
            short_critical_text: Some(format!("{progress}%")),
            large_icon: Some(icon.clone()),
            actions: vec![NotificationAction {
                icon: Some(icon),
                ..NotificationAction::new("track", "Track")
            }],
            samsung_now_bar: Some(SamsungNowBar {
                primary: Some("Mochi delivery".into()),
                secondary: Some(status.into()),
            }),
            ..AndroidPresentation::new(style)
        }),
    }
}

/// Starts, updates and ends one delivery.
pub fn run_delivery<D: Driver>(updates: &LiveUpdate<D>) -> Result<()> {
    if !updates.capabilities()?.can_post() {
        return Ok(()); // Ask for the notification permission first.
    }
    let activity = updates.start(StartRequest {
        placement: Placement::Tagged,
        start_at: None,
        activity_key: "delivery:order-123".into(),
        schema_version: 2,
        template: Template::Progress,
        fallback: Fallback::Notification,
        visibility: Visibility::Private,
        snapshot: delivery_snapshot(0, 10, "Preparing"),
    })?;

    updates.update(&activity.id, delivery_snapshot(1, 65, "On the way"))?;

    updates.end(&activity.id, EndPolicy::Immediate)?;
    Ok(())
}

/// Call this once the Android runtime has initialized `ndk-context`, and keep the client in
/// application state.
#[cfg(target_os = "android")]
pub fn on_android() -> Result<()> {
    run_delivery(&LiveUpdate::android()?)
}
