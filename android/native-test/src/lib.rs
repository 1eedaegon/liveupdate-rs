//! The native half of the instrumentation tests: it drives `liveupdate` through JNI the way an
//! application's Rust code does, and reports each scenario's outcome as JSON.
//!
//! `NativeRust.initialize` plays the host runtime that sets up `ndk-context`; `release` undoes it.
//! `NativeRust.run(scenario, input)` runs one scenario on a thread Rust creates (or, for
//! `*-on-caller`, on the Java thread that called) and answers `{"ok": value}`,
//! `{"error": {"code", "message"}}` or `{"panic": message}`.

use jni::{
    Env, EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    objects::{Global, JObject, JString},
};
use liveupdate::{
    ActivityRecord, AndroidDriver, EndPolicy, ErrorCode, Fallback, LiveUpdate, Snapshot,
    StartRequest, Template, Visibility,
};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{Arc, Mutex, PoisonError},
    thread,
};

/// The context `ndk-context` currently points at, kept alive by this global reference.
static CONTEXT: Mutex<Option<Global<JObject<'static>>>> = Mutex::new(None);

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_gon_liveupdate_NativeRust_initialize<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    context: JObject<'caller>,
) {
    unowned_env
        .with_env(|env| initialize(env, context))
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_gon_liveupdate_NativeRust_release<'caller>(
    _unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
) {
    release();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_gon_liveupdate_NativeRust_run<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    scenario: JString<'caller>,
    input: JString<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| {
            let scenario = scenario.mutf8_chars(env)?.to_string();
            let input = input.mutf8_chars(env)?.to_string();
            let outcome = match scenario.strip_suffix("-on-caller") {
                Some(name) => outcome(name, &input),
                None => {
                    let name = scenario.clone();
                    thread::spawn(move || outcome(&name, &input))
                        .join()
                        .unwrap_or_else(|_| json!({ "panic": "the scenario thread panicked" }))
                }
            };
            env.new_string(outcome.to_string())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_gon_liveupdate_NativeRust_writeCoverage<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    path: JString<'caller>,
) -> bool {
    unowned_env
        .with_env(|env| -> jni::errors::Result<bool> {
            let path = path.mutf8_chars(env)?.to_string();
            Ok(coverage::write(&path))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn initialize(env: &mut Env<'_>, context: JObject<'_>) -> jni::errors::Result<()> {
    let mut current = CONTEXT.lock().unwrap_or_else(PoisonError::into_inner);
    let context = env.new_global_ref(context)?;
    let vm = env.get_java_vm()?;
    if current.take().is_some() {
        // SAFETY: this library initialized the context it now releases.
        unsafe { ndk_context::release_android_context() };
    }
    // SAFETY: the VM is the process VM and the global reference stays alive in `CONTEXT` until
    // `release` or the next `initialize` replaces it.
    unsafe {
        ndk_context::initialize_android_context(vm.get_raw().cast(), context.as_raw().cast());
    }
    *current = Some(context);
    Ok(())
}

fn release() {
    let mut current = CONTEXT.lock().unwrap_or_else(PoisonError::into_inner);
    if current.take().is_some() {
        // SAFETY: this library initialized the context it now releases.
        unsafe { ndk_context::release_android_context() };
    }
}

fn outcome(scenario: &str, input: &str) -> Value {
    match catch_unwind(AssertUnwindSafe(|| scenarios::run(scenario, input))) {
        Ok(Ok(value)) => json!({ "ok": value }),
        Ok(Err(error)) => json!({ "error": { "code": error.code, "message": error.message } }),
        Err(panic) => {
            let message = panic
                .downcast_ref::<&str>()
                .map(|message| message.to_string())
                .or_else(|| panic.downcast_ref::<String>().cloned())
                .unwrap_or_default();
            json!({ "panic": message })
        }
    }
}

mod scenarios {
    use super::*;

    type Client = LiveUpdate<AndroidDriver>;

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Update {
        id: String,
        snapshot: Snapshot,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct End {
        id: String,
        policy: EndPolicy,
    }

    pub(super) fn run(scenario: &str, input: &str) -> liveupdate::Result<Value> {
        match scenario {
            "new" => LiveUpdate::android().map(|_| Value::Null),
            "capabilities" => Ok(json!(LiveUpdate::android()?.capabilities()?)),
            "start" => record(LiveUpdate::android()?.start(parse(input)?)?),
            "update" => {
                let update: Update = parse(input)?;
                record(LiveUpdate::android()?.update(&update.id, update.snapshot)?)
            }
            "end" => {
                let end: End = parse(input)?;
                record(LiveUpdate::android()?.end(&end.id, end.policy)?)
            }
            "list" => Ok(json!(LiveUpdate::android()?.list()?)),
            "events" => Ok(json!(LiveUpdate::android()?.events()?)),
            "lifecycle" => lifecycle(&LiveUpdate::android()?),
            "threads" => threads(Arc::new(LiveUpdate::android()?)),
            "calls" => calls(&LiveUpdate::android()?, parse(input)?),
            _ => Err(liveupdate::Error::new(
                ErrorCode::InvalidInput,
                "Unknown scenario.",
            )),
        }
    }

    fn parse<T: for<'de> Deserialize<'de>>(input: &str) -> liveupdate::Result<T> {
        serde_json::from_str(input)
            .map_err(|_| liveupdate::Error::new(ErrorCode::InvalidInput, "Unreadable input."))
    }

    fn record(record: ActivityRecord) -> liveupdate::Result<Value> {
        Ok(json!(record))
    }

    fn lifecycle(updates: &Client) -> liveupdate::Result<Value> {
        let capabilities = updates.capabilities()?;
        let started = updates.start(request("native:lifecycle", 0, 0.25))?;
        let updated = updates.update(&started.id, snapshot(1, 0.75))?;
        let listed = updates.list()?;
        let ended = updates.end(&started.id, EndPolicy::Immediate)?;
        Ok(json!({
            "notificationsEnabled": capabilities.notifications_enabled,
            "startedId": started.id,
            "updatedRevision": updated.snapshot.revision,
            "listedRevision": listed.iter()
                .find(|record| record.id == started.id)
                .map(|record| record.snapshot.revision),
            "endedState": ended.state,
        }))
    }

    /// Eight Rust threads share one client: each runs its own activity, and eight more start the
    /// same key at once.
    fn threads(updates: Arc<Client>) -> liveupdate::Result<Value> {
        let own: Vec<_> = (0..8)
            .map(|index| {
                let updates = Arc::clone(&updates);
                thread::spawn(move || -> liveupdate::Result<String> {
                    let key = format!("native:thread:{index}");
                    let started = updates.start(request(&key, 0, 0.1))?;
                    updates.update(&started.id, snapshot(1, 0.9))?;
                    Ok(format!(
                        "{:?}",
                        updates.end(&started.id, EndPolicy::Immediate)?.state
                    ))
                })
            })
            .collect();
        let shared: Vec<_> = (0..8)
            .map(|_| {
                let updates = Arc::clone(&updates);
                thread::spawn(move || {
                    updates
                        .start(request("native:shared", 0, 0.5))
                        .map(|record| record.id)
                })
            })
            .collect();
        let ended = own
            .into_iter()
            .map(|thread| thread.join().expect("thread panicked"))
            .collect::<liveupdate::Result<Vec<_>>>()?;
        let mut ids = shared
            .into_iter()
            .map(|thread| thread.join().expect("thread panicked"))
            .collect::<liveupdate::Result<Vec<_>>>()?;
        ids.sort();
        ids.dedup();
        let shared_id = ids.first().cloned().unwrap_or_default();
        updates.end(&shared_id, EndPolicy::Immediate)?;
        Ok(json!({ "ended": ended, "sharedIds": ids.len() }))
    }

    /// Many calls in a row on one thread, as a polling application makes them. Reports the record's
    /// state three seconds after the last update: `unknown` when Android dropped that update.
    fn calls(updates: &Client, count: usize) -> liveupdate::Result<Value> {
        let started = updates.start(request("native:calls", 0, 0.0))?;
        for revision in 1..=count as u64 {
            updates.list()?;
            updates.capabilities()?;
            updates.update(
                &started.id,
                snapshot(revision, revision as f64 / count as f64),
            )?;
        }
        thread::sleep(std::time::Duration::from_secs(3));
        let after = updates
            .list()?
            .into_iter()
            .find(|record| record.id == started.id)
            .map(|record| record.state);
        updates.end(&started.id, EndPolicy::Immediate)?;
        Ok(json!({ "count": count, "stateAfter": after }))
    }

    fn request(key: &str, revision: u64, progress: f64) -> StartRequest {
        StartRequest {
            activity_key: key.into(),
            schema_version: 1,
            template: Template::Progress,
            fallback: Fallback::Notification,
            visibility: Visibility::Private,
            snapshot: snapshot(revision, progress),
        }
    }

    fn snapshot(revision: u64, progress: f64) -> Snapshot {
        Snapshot {
            revision,
            title: "Mochi's snack delivery".into(),
            status: "Native Rust".into(),
            progress: Some(progress),
            timer: None,
            stale_at: None,
            presentation: None,
        }
    }
}

/// Writes the LLVM coverage counters of this library when it was built with
/// `-C instrument-coverage` and the `coverage` feature; otherwise does nothing.
mod coverage {
    #[cfg(feature = "coverage")]
    pub(super) fn write(path: &str) -> bool {
        use std::ffi::{CString, c_char, c_int};
        unsafe extern "C" {
            fn __llvm_profile_set_filename(name: *const c_char);
            fn __llvm_profile_write_file() -> c_int;
        }
        let Ok(path) = CString::new(path) else {
            return false;
        };
        // SAFETY: both functions come from the profiler runtime that `-C instrument-coverage`
        // links; the runtime copies the file name before it returns.
        unsafe {
            __llvm_profile_set_filename(path.as_ptr());
            __llvm_profile_write_file() == 0
        }
    }

    #[cfg(not(feature = "coverage"))]
    pub(super) fn write(_path: &str) -> bool {
        false
    }
}
