//! One delivery from start to end. `run_delivery` works with any `Driver`, so tests can pass a fake
//! one; on Android, `on_android` hands it the client of the companion AAR.

use liveupdate::{
    Driver, EndPolicy, Fallback, LiveUpdate, Result, Snapshot, StartRequest, Template, Visibility,
};

/// Starts, updates and ends one delivery.
pub fn run_delivery<D: Driver>(updates: &LiveUpdate<D>) -> Result<()> {
    if !updates.capabilities()?.can_post() {
        return Ok(()); // Ask for the notification permission first.
    }
    let activity = updates.start(StartRequest {
        activity_key: "delivery:order-123".into(),
        schema_version: 1,
        template: Template::Progress,
        fallback: Fallback::Notification,
        visibility: Visibility::Private,
        snapshot: Snapshot {
            revision: 0,
            title: "Mochi's snack delivery".into(),
            status: "Preparing".into(),
            progress: Some(0.1),
            timer: None,
            stale_at: None,
            presentation: None,
        },
    })?;

    updates.update(
        &activity.id,
        Snapshot {
            revision: 1,
            title: "Mochi's snack delivery".into(),
            status: "On the way".into(),
            progress: Some(0.65),
            timer: None,
            stale_at: None,
            presentation: None,
        },
    )?;

    updates.end(&activity.id, EndPolicy::Immediate)?;
    Ok(())
}

/// Call this once the Android runtime has initialized `ndk-context`, and keep the client in
/// application state.
#[cfg(target_os = "android")]
pub fn on_android() -> Result<()> {
    run_delivery(&LiveUpdate::android()?)
}
