//! One delivery from start to end. `run_delivery` works with any `Driver`, so tests can pass a fake
//! one; on Android, `on_android` hands it the client of the companion AAR.

#![deny(clippy::unwrap_used, clippy::expect_used)]

use liveupdate::{
    AndroidPresentation, Driver, EndPolicy, Fallback, IconSource, LiveUpdate, NotificationAction,
    PresentationStyle, ProgressPoint, ProgressSegment, ProgressStyle, Result, SamsungNowBar,
    SemanticStyle, Snapshot, StartRequest, Visibility,
};

/// Each value is built on its constructor, which takes what is required: a member that a later
/// version adds is then left as it is by default.
fn delivery_progress(progress: u32, icon: &IconSource) -> ProgressStyle {
    let segments = vec![
        ProgressSegment {
            id: Some(1),
            color: Some(0xff21_96f3),
            semantic_style: Some(SemanticStyle::Info),
            ..ProgressSegment::new(25)
        },
        ProgressSegment {
            id: Some(2),
            color: Some(0xff4c_af50),
            semantic_style: Some(SemanticStyle::Safe),
            ..ProgressSegment::new(75)
        },
    ];
    ProgressStyle {
        styled_by_progress: true,
        points: vec![ProgressPoint {
            id: Some(10),
            color: Some(0xffff_a000),
            semantic_style: Some(SemanticStyle::Caution),
            ..ProgressPoint::new(25)
        }],
        start_icon: Some(icon.clone()),
        end_icon: Some(icon.clone()),
        tracker_icon: Some(icon.clone()),
        ..ProgressStyle::new(progress, segments)
    }
}

/// Schema-v2 content for Android's rich progress presentation.
fn delivery_snapshot(revision: u64, progress: u32, status: &str) -> Snapshot {
    let icon = IconSource::Resource("liveupdate_status".into());
    let style = PresentationStyle::Progress(delivery_progress(progress, &icon));
    Snapshot {
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
        ..Snapshot::new(revision, "Mochi's snack delivery", status)
    }
}

/// Starts, updates and ends one delivery.
pub fn run_delivery<D: Driver>(updates: &LiveUpdate<D>) -> Result<()> {
    if !updates.capabilities()?.can_post() {
        return Ok(()); // Ask for the notification permission first.
    }
    let activity = updates.start(StartRequest::new(
        "delivery:order-123",
        Fallback::Notification,
        Visibility::Private,
        delivery_snapshot(0, 10, "Preparing"),
    ))?;

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
