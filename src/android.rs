use crate::{Bridge, Error, JsonDriver, LiveUpdate, Result};
use jni::{
    Env, JavaVM, jni_sig, jni_str,
    objects::{Global, JClass, JObject, JString, JValue},
};
use std::{
    ffi::c_void,
    panic::catch_unwind,
    sync::{Mutex, PoisonError},
};

const BRIDGE_CLASS: &str = "io.github.eedaegon.liveupdate.LiveUpdateBridge";
static CONTEXT_INITIALIZATION: Mutex<()> = Mutex::new(());

/// JNI transport supplied by the companion `liveupdate-android` AAR.
pub struct AndroidBridge {
    vm: JavaVM,
    context: Global<JObject<'static>>,
    class: Global<JClass<'static>>,
}

impl AndroidBridge {
    /// Captures the process application context and loads the AAR bridge class.
    ///
    /// The Android host must initialize [`ndk_context`] before this method is
    /// called. Android native activity and common Rust window runtimes normally
    /// do this while loading the application's native library. Called earlier,
    /// it returns [`crate::ErrorCode::NativeError`] (`ndk-context` panics, and
    /// this method catches that panic unless the build aborts on panic), and a
    /// later call after the initialization succeeds.
    pub fn new() -> Result<Self> {
        let android = catch_unwind(ndk_context::android_context).map_err(|_| Error::native())?;
        // The lock guards no data, so a panic that poisoned it leaves nothing to repair.
        let _initialization = CONTEXT_INITIALIZATION
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        // SAFETY: ndk-context is initialized by the Android host and guarantees
        // a valid process JavaVM pointer for the lifetime of the application.
        let vm = unsafe { JavaVM::from_raw(android.vm().cast()) };
        let (context, class) = vm
            .attach_current_thread(|env| capture_android(env, android.context().cast()))
            .map_err(|_| Error::native())?;
        Ok(Self { vm, context, class })
    }

    /// Captures the application context of `context` and loads the AAR bridge class without
    /// [`ndk_context`]: for a host's own JNI entry, such as that of a service that runs with no
    /// activity and no Rust runtime. The bridge keeps a global reference; it may then be used on
    /// any thread.
    ///
    /// # Safety
    ///
    /// `vm` must be the process's `JavaVM` (`JNIEnv::GetJavaVM`, or `jni::JavaVM::get_raw`), and
    /// `context` a JNI reference to an `android.content.Context`, local or global, that is valid
    /// on the calling thread for the whole call. Null is answered with
    /// [`crate::ErrorCode::NativeError`].
    pub unsafe fn from_raw(vm: *mut c_void, context: *mut c_void) -> Result<Self> {
        if vm.is_null() || context.is_null() {
            return Err(Error::native());
        }
        let _initialization = CONTEXT_INITIALIZATION
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        // SAFETY: the caller guarantees the process's JavaVM.
        let vm = unsafe { JavaVM::from_raw(vm.cast()) };
        let (context, class) = vm
            .attach_current_thread(|env| capture_android(env, context.cast()))
            .map_err(|_| Error::native())?;
        Ok(Self { vm, context, class })
    }
}

impl Bridge for AndroidBridge {
    fn invoke(&self, command: &str, payload: &str) -> Result<String> {
        self.vm
            .attach_current_thread(|env| invoke(env, &self.context, &self.class, command, payload))
            .map_err(|_| Error::native())
    }
}

/// Android framework driver used by [`LiveUpdate::android`].
pub type AndroidDriver = JsonDriver<AndroidBridge>;

impl LiveUpdate<AndroidDriver> {
    /// Creates a live update client backed by the companion Android AAR.
    pub fn android() -> Result<Self> {
        Ok(Self::new(JsonDriver::new(AndroidBridge::new()?)))
    }

    /// Creates a live update client from the process's `JavaVM` and a context, without
    /// [`ndk_context`]: for a service's own JNI entry, in a process that the service started.
    ///
    /// # Safety
    ///
    /// As [`AndroidBridge::from_raw`]: `vm` is the process's `JavaVM`, and `context` a reference
    /// to an `android.content.Context` that is valid on the calling thread for the whole call.
    pub unsafe fn android_from_raw(vm: *mut c_void, context: *mut c_void) -> Result<Self> {
        // SAFETY: the caller's guarantee, passed on.
        let bridge = unsafe { AndroidBridge::from_raw(vm, context)? };
        Ok(Self::new(JsonDriver::new(bridge)))
    }
}

fn capture_android(
    env: &mut Env<'_>,
    raw_context: jni::sys::jobject,
) -> jni::errors::Result<(Global<JObject<'static>>, Global<JClass<'static>>)> {
    // SAFETY: ndk-context, or the caller of `AndroidBridge::from_raw`, guarantees this non-null
    // Context reference. The initialization mutex ensures this temporary wrapper is unique here.
    let context = unsafe { JObject::from_raw(env, raw_context) };
    let application = env
        .call_method(
            &context,
            jni_str!("getApplicationContext"),
            jni_sig!("()Landroid/content/Context;"),
            &[],
        )?
        .l()?;
    let application = if application.is_null() {
        env.new_global_ref(&context)?
    } else {
        env.new_global_ref(application)?
    };
    let class = load_bridge_class(env, &application)?;
    Ok((application, class))
}

fn load_bridge_class(
    env: &mut Env<'_>,
    context: &Global<JObject<'static>>,
) -> jni::errors::Result<Global<JClass<'static>>> {
    let loader = env
        .call_method(
            context,
            jni_str!("getClassLoader"),
            jni_sig!("()Ljava/lang/ClassLoader;"),
            &[],
        )?
        .l()?;
    let name = env.new_string(BRIDGE_CLASS)?;
    let class = env
        .call_method(
            &loader,
            jni_str!("loadClass"),
            jni_sig!("(Ljava/lang/String;)Ljava/lang/Class;"),
            &[JValue::Object(name.as_ref())],
        )?
        .l()?;
    let class = JClass::cast_local(env, class)?;
    env.new_global_ref(class)
}

fn invoke(
    env: &mut Env<'_>,
    context: &Global<JObject<'static>>,
    class: &Global<JClass<'static>>,
    command: &str,
    payload: &str,
) -> jni::errors::Result<String> {
    let command = env.new_string(command)?;
    let payload = env.new_string(payload)?;
    let output = env
        .call_static_method(
            class,
            jni_str!("invoke"),
            jni_sig!(
                "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
            ),
            &[
                JValue::Object(context.as_ref()),
                JValue::Object(command.as_ref()),
                JValue::Object(payload.as_ref()),
            ],
        )?
        .l()?;
    let output = JString::cast_local(env, output)?;
    Ok(output.mutf8_chars(env)?.to_string())
}

/// The entry of the AAR's `LiveUpdatePush.handle`, exported from the host's library: it runs the member
/// of a data message through a client built from `context` and answers what it did as JSON.
#[cfg(feature = "push")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_eedaegon_liveupdate_LiveUpdatePush_nativeHandle<'caller>(
    mut unowned_env: jni::EnvUnowned<'caller>,
    _class: JClass<'caller>,
    context: JObject<'caller>,
    member: JString<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| {
            let member = member.mutf8_chars(env)?.to_string();
            let vm = env.get_java_vm()?;
            let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
                push(vm.get_raw().cast(), context.as_raw().cast(), &member)
            }))
            .unwrap_or_else(|_| crate::push::outcome(None, Err(crate::ErrorCode::NativeError)));
            env.new_string(outcome.to_string())
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

#[cfg(feature = "push")]
fn push(vm: *mut c_void, context: *mut c_void, member: &str) -> serde_json::Value {
    let payload = match crate::push::Payload::parse(member) {
        Ok(payload) => payload,
        Err(error) => return crate::push::outcome(None, Err(error.code)),
    };
    // SAFETY: the process VM, and a context reference that is valid on this thread for the call.
    let result = unsafe { LiveUpdate::android_from_raw(vm, context) }
        .and_then(|client| client.push(&payload));
    match result {
        Ok(record) => crate::push::outcome(Some(&payload), Ok(&record.id)),
        Err(error) => crate::push::outcome(Some(&payload), Err(error.code)),
    }
}
