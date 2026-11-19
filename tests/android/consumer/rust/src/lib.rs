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
