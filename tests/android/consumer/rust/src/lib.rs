//! An application's Rust code, as the README has it: one client, kept, and the crate's own types. Each
//! scenario answers `{"ok": value}` or `{"error": {"code", "message"}}`.

use jni::{
    Env, EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    objects::{Global, JObject, JString},
};
use liveupdate::{
    ActivityRecord, AndroidDriver, AndroidPresentation, EndPolicy, Fallback, IconSource,
    LiveUpdate, NotificationAction, PresentationStyle, ProgressSegment, ProgressStyle, Result,
    Snapshot, StartRequest, Visibility,
};
use serde_json::{Value, json};
use std::{
    sync::{Mutex, OnceLock, PoisonError},
    time::{SystemTime, UNIX_EPOCH},
};

const RIDE: &str = "consumer:ride";
const LATER: &str = "consumer:later";
/// The key of the ride that the application's server begins: another than the ride's, which `run` ended.
#[cfg(feature = "push")]
const PUSHED: &str = "consumer:pushed";
const ICON: &str = "consumer_status";

static CONTEXT: Mutex<Option<Global<JObject<'static>>>> = Mutex::new(None);
static CLIENT: OnceLock<LiveUpdate<AndroidDriver>> = OnceLock::new();

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_eedaegon_liveupdate_consumer_Native_initialize<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    context: JObject<'caller>,
) {
    unowned_env
        .with_env(|env| initialize(env, context))
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_eedaegon_liveupdate_consumer_Native_run<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    scenario: JString<'caller>,
    input: JString<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| {
            let scenario = scenario.mutf8_chars(env)?.to_string();
            let input = input.mutf8_chars(env)?.to_string();
            let answer = match run(&scenario, &input) {
                Ok(value) => json!({ "ok": value }),
                Err(error) => json!({ "error": { "code": error.code, "message": error.message } }),
            };
            env.new_string(answer.to_string())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// A client built from the context alone, without `ndk-context`, as a service's own entry builds it.
#[cfg(feature = "targets")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_eedaegon_liveupdate_consumer_Native_runFrom<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    context: JObject<'caller>,
    scenario: JString<'caller>,
    input: JString<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| {
            let scenario = scenario.mutf8_chars(env)?.to_string();
            let input = input.mutf8_chars(env)?.to_string();
            let vm = env.get_java_vm()?;
            // SAFETY: the process VM, and a context reference that is valid on this thread for the call.
            let client = unsafe {
                LiveUpdate::android_from_raw(vm.get_raw().cast(), context.as_raw().cast())
            };
            let answer = match client.and_then(|client| recorder::run(&client, &scenario, &input)) {
                Ok(value) => json!({ "ok": value }),
                Err(error) => json!({ "error": { "code": error.code, "message": error.message } }),
            };
            env.new_string(answer.to_string())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn initialize(env: &mut Env<'_>, context: JObject<'_>) -> jni::errors::Result<()> {
    let mut current = CONTEXT.lock().unwrap_or_else(PoisonError::into_inner);
    if current.is_some() {
        return Ok(());
    }
    let context = env.new_global_ref(context)?;
    let vm = env.get_java_vm()?;
    // SAFETY: the process VM, and an application context that `CONTEXT` keeps alive for the process.
    unsafe {
        ndk_context::initialize_android_context(vm.get_raw().cast(), context.as_raw().cast());
    }
    *current = Some(context);
    Ok(())
}

/// The one client of the process, as the README asks.
fn client() -> Result<&'static LiveUpdate<AndroidDriver>> {
    if let Some(client) = CLIENT.get() {
        return Ok(client);
    }
    let created = LiveUpdate::android()?;
    Ok(CLIENT.get_or_init(|| created))
}

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|time| time.as_millis() as u64)
        .unwrap_or_default()
}

/// The ride's content at `revision`: a progress bar, the application's own icons, a button that opens a
/// screen and one that asks to unlock first.
fn ride(revision: u64, progress: u32, carried: bool) -> Snapshot {
    let icon = IconSource::Resource(ICON.into());
    let style = PresentationStyle::Progress(ProgressStyle::new(
        progress,
        vec![ProgressSegment::new(100)],
    ));
    Snapshot {
        presentation: Some(AndroidPresentation {
            small_icon: Some(icon.clone()),
            large_icon: Some(icon),
            short_critical_text: Some(format!("{progress}%")),
            actions: vec![
                NotificationAction {
                    open_url: Some("liveupdate-consumer://order/42".into()),
                    ..NotificationAction::new("track", "Track")
                },
                NotificationAction {
                    authentication_required: true,
                    ..NotificationAction::new("pay", "Pay")
                },
            ],
            wear_ongoing_activity: carried,
            ..AndroidPresentation::new(style)
        }),
        ..Snapshot::new(revision, "Consumer ride", format!("{progress} of 100"))
    }
}

/// The member of a data message for the ride: `{"command": "start" | "update" | "end", "revision": n}`.
#[cfg(feature = "push")]
fn payload(input: &Value) -> Result<Value> {
    use liveupdate::push::{DATA_KEY, Payload};
    let revision = input["revision"].as_u64().unwrap_or(0);
    let payload = match input["command"].as_str() {
        Some("start") => Payload::start(StartRequest::new(
            PUSHED,
            Fallback::Notification,
            Visibility::Public,
            ride(0, 30, true),
        )),
        Some("update") => Payload::update(PUSHED, ride(revision, 50, true)),
        Some("end") => Payload::end(PUSHED, EndPolicy::Immediate),
        _ => {
            return Err(liveupdate::Error::new(
                liveupdate::ErrorCode::InvalidInput,
                "Unknown command.",
            ));
        }
    };
    Ok(Value::String(payload.to_data()?.remove(DATA_KEY).unwrap_or_default()))
}

fn brief(record: &ActivityRecord) -> Value {
    json!({
        "id": record.id,
        "key": record.activity_key,
        "revision": record.snapshot.revision,
        "state": record.state,
        "promotion": record.promotion_state,
    })
}

fn live(key: &str) -> Result<Option<ActivityRecord>> {
    let records = client()?.list()?;
    Ok(records.into_iter().find(|record| {
        record.activity_key == key
            && !matches!(
                record.state,
                liveupdate::ActivityState::Ended | liveupdate::ActivityState::Dismissed
            )
    }))
}

fn run(scenario: &str, input: &str) -> Result<Value> {
    let input: Value = serde_json::from_str(input).unwrap_or(Value::Null);
    let client = client()?;
    match scenario {
        "capabilities" => Ok(json!(client.capabilities()?)),
        "start" => {
            let request = StartRequest::new(
                RIDE,
                Fallback::Notification,
                Visibility::Public,
                ride(0, 30, true),
            );
            Ok(brief(&client.start(request)?))
        }
        "update" | "quick-end" => {
            let Some(record) = live(RIDE)? else {
                return Ok(Value::Null);
            };
            let carried = input["wear"].as_bool().unwrap_or(true);
            let next = record.snapshot.revision + 1;
            let updated = client.update(&record.id, ride(next, 70, carried))?;
            if scenario == "update" {
                return Ok(brief(&updated));
            }
            // At once after the update: the last content, which stays a minute.
            let ended = client.end(&record.id, EndPolicy::After { at: now() + 60_000 })?;
            Ok(json!([brief(&updated), brief(&ended)]))
        }
        "schedule" => {
            let delay = input["delayMs"].as_u64().unwrap_or(20_000);
            let request = StartRequest {
                start_at: Some(now() + delay),
                ..StartRequest::new(
                    LATER,
                    Fallback::Notification,
                    Visibility::Public,
                    ride(0, 10, true),
                )
            };
            Ok(brief(&client.start(request)?))
        }
        "list" => Ok(json!(client.list()?.iter().map(brief).collect::<Vec<_>>())),
        "changes" => Ok(json!(
            client
                .state_changes()?
                .iter()
                .map(brief)
                .collect::<Vec<_>>()
        )),
        "events" => Ok(json!(client.events()?)),
        #[cfg(feature = "targets")]
        "recorder" => Ok(brief(&client.start(recorder::request(0, false))?)),
        #[cfg(feature = "targets")]
        "recording" => recorder::run(client, "recording", &input.to_string()),
        #[cfg(feature = "platform")]
        "settings" => client.open_promotion_settings().map(|()| Value::Null),
        // What the application's server sends for the ride, as `consumer.sh` hands it to the push's receiver.
        #[cfg(feature = "push")]
        "payload" => payload(&input),
        "end" => {
            let mut ended = Vec::new();
            for record in client.list()? {
                if !matches!(
                    record.state,
                    liveupdate::ActivityState::Ended | liveupdate::ActivityState::Dismissed
                ) {
                    ended.push(brief(&client.end(&record.id, EndPolicy::Immediate)?));
                }
            }
            Ok(json!(ended))
        }
        _ => Ok(json!({ "unknown": scenario })),
    }
}

/// Fremen's shape: an activity that a service holds, whose Record action starts or reaches the service.
#[cfg(feature = "targets")]
mod recorder {
    use super::*;
    use liveupdate::{ActionTarget, Placement, TargetKind};

    const KEY: &str = "consumer:recorder";
    const SERVICE: &str = "io.github.eedaegon.liveupdate.consumer.RecorderService";

    pub(super) fn request(revision: u64, recording: bool) -> StartRequest {
        StartRequest {
            placement: Placement::Foreground,
            ..StartRequest::new(
                KEY,
                Fallback::Notification,
                Visibility::Public,
                snapshot(revision, recording),
            )
        }
    }

    fn snapshot(revision: u64, recording: bool) -> Snapshot {
        let target = |action: &str| ActionTarget {
            action: Some(format!("io.github.eedaegon.liveupdate.consumer.{action}")),
            ..ActionTarget::new(TargetKind::ForegroundService, SERVICE)
        };
        let record = NotificationAction {
            target: Some(target("RECORD")),
            ..NotificationAction::new("record", if recording { "Stop" } else { "Record" })
        };
        // A button that asks for the unlock: below Android 12 the service hears it withheld while locked.
        let discard = NotificationAction {
            target: Some(target("DISCARD")),
            authentication_required: true,
            ..NotificationAction::new("discard", "Discard")
        };
        Snapshot {
            presentation: Some(AndroidPresentation {
                small_icon: Some(IconSource::Resource(ICON.into())),
                short_critical_text: Some(if recording { "REC" } else { "LISTEN" }.into()),
                actions: vec![record, discard],
                ..AndroidPresentation::new(PresentationStyle::Standard)
            }),
            ..Snapshot::new(
                revision,
                "Consumer recorder",
                if recording { "Recording" } else { "Listening" },
            )
        }
    }

    pub(super) fn run(
        client: &LiveUpdate<AndroidDriver>,
        scenario: &str,
        input: &str,
    ) -> Result<Value> {
        let input: Value = serde_json::from_str(input).unwrap_or(Value::Null);
        match scenario {
            "recording" => {
                let recording = input["on"].as_bool().unwrap_or(false);
                let records = client.list()?;
                let Some(record) = records.iter().find(|record| {
                    record.activity_key == KEY
                        && !matches!(
                            record.state,
                            liveupdate::ActivityState::Ended | liveupdate::ActivityState::Dismissed
                        )
                }) else {
                    return Ok(Value::Null);
                };
                let next = record.snapshot.revision + 1;
                Ok(brief(
                    &client.update(&record.id, snapshot(next, recording))?,
                ))
            }
            _ => Ok(json!({ "unknown": scenario })),
        }
    }
}
