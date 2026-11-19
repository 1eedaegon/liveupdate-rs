//! A recorder whose button runs the application's own service at once, and the service's own entry
//! into Rust, which needs no activity and no `ndk-context`.

#![deny(clippy::unwrap_used, clippy::expect_used)]

use liveupdate::{
    ActionTarget, ActivityRecord, ActivityState, AndroidPresentation, Driver, Fallback, LiveUpdate,
    NotificationAction, Placement, PresentationStyle, Result, Snapshot, StartRequest, TargetKind,
    Visibility,
};

const RECORDER: &str = "recorder";

/// The Record button: its tap starts or reaches `RecorderService`, which takes it first with
/// `LiveUpdateActions.take`.
fn record_button(recording: bool) -> NotificationAction {
    NotificationAction {
        target: Some(ActionTarget {
            action: Some("com.example.RECORD".into()),
            ..ActionTarget::new(TargetKind::ForegroundService, "com.example.RecorderService")
        }),
        ..NotificationAction::new("record", if recording { "Stop" } else { "Record" })
    }
}

fn recorder(revision: u64, recording: bool) -> Snapshot {
    Snapshot {
        presentation: Some(AndroidPresentation {
            short_critical_text: Some(if recording { "REC" } else { "LISTEN" }.into()),
            actions: vec![record_button(recording)],
            ..AndroidPresentation::new(PresentationStyle::Standard)
        }),
        ..Snapshot::new(
            revision,
            "Recorder",
            if recording { "Recording" } else { "Listening" },
        )
    }
}

/// Shows whether the recorder records: starts its activity, which the service holds, or updates it.
pub fn show_recording<D: Driver>(
    client: &LiveUpdate<D>,
    recording: bool,
) -> Result<ActivityRecord> {
    let running = client.list()?.into_iter().find(|record| {
        record.activity_key == RECORDER
            && !matches!(
                record.state,
                ActivityState::Ended | ActivityState::Dismissed
            )
    });
    match running {
        Some(record) => client.update(
            &record.id,
            recorder(record.snapshot.revision + 1, recording),
        ),
        None => client.start(StartRequest {
            placement: Placement::Foreground,
            ..StartRequest::new(
                RECORDER,
                Fallback::Notification,
                Visibility::Public,
                recorder(0, recording),
            )
        }),
    }
}

/// `Native.showRecording(context, recording)`, which the service calls off its main thread: the
/// client is built from the context it is given.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_Native_showRecording<'a>(
    mut env: jni::EnvUnowned<'a>,
    _this: jni::objects::JObject<'a>,
    context: jni::objects::JObject<'a>,
    recording: bool,
) -> bool {
    env.with_env(|env| -> jni::errors::Result<bool> {
        let vm = env.get_java_vm()?;
        // SAFETY: the process's VM, and a context that is valid on this thread for the call.
        let client =
            unsafe { LiveUpdate::android_from_raw(vm.get_raw().cast(), context.as_raw().cast()) };
        let shown = client.and_then(|client| show_recording(&client, recording));
        // A start that fails, as SYSTEM_LIMIT does, leaves the service's own notification.
        Ok(shown.is_ok())
    })
    .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
