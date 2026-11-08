use jni::{
    Env, EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    objects::{Global, JObject, JString},
};
use liveupdate::{EndPolicy, Fallback, LiveUpdate, Snapshot, StartRequest, Template, Visibility};
use serde_json::{Value, json};
use std::sync::{Mutex, OnceLock};

static ANDROID_CONTEXT: OnceLock<Global<JObject<'static>>> = OnceLock::new();
static INITIALIZATION: Mutex<()> = Mutex::new(());

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_eedaegon_liveupdate_NativeRust_run<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    context: JObject<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| {
            initialize_context(env, context)?;
            let response = std::thread::spawn(run_lifecycle)
                .join()
                .unwrap_or_else(|_| json!({ "error": "native thread failed" }));
            env.new_string(response.to_string())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn initialize_context(env: &mut Env<'_>, context: JObject<'_>) -> jni::errors::Result<()> {
    let _initialization = INITIALIZATION
        .lock()
        .expect("initialization mutex poisoned");
    if ANDROID_CONTEXT.get().is_some() {
        return Ok(());
    }
    let context = env.new_global_ref(context)?;
    let vm = env.get_java_vm()?;
    // SAFETY: both references are global process references retained for the
    // complete instrumentation process, and initialization runs exactly once.
    unsafe {
        ndk_context::initialize_android_context(vm.get_raw().cast(), context.as_raw().cast());
    }
    let _ = ANDROID_CONTEXT.set(context);
    Ok(())
}

fn run_lifecycle() -> Value {
    match try_run_lifecycle() {
        Ok(value) => value,
        Err(error) => json!({
            "error": {
                "code": error.code,
                "message": error.message,
            }
        }),
    }
}

fn try_run_lifecycle() -> liveupdate::Result<Value> {
    let updates = LiveUpdate::android()?;
    let capabilities = updates.capabilities()?;
    let started = updates.start(StartRequest {
        activity_key: "native:instrumentation".into(),
        schema_version: 1,
        template: Template::Progress,
        fallback: Fallback::Notification,
        visibility: Visibility::Private,
        snapshot: snapshot(0, 0.25),
    })?;
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

fn snapshot(revision: u64, progress: f64) -> Snapshot {
    Snapshot {
        revision,
        title: "Mochi's snack delivery".into(),
        status: "Native Rust".into(),
        progress: Some(progress),
        timer: None,
        stale_at: None,
    }
}
