use crate::{Bridge, Error, JsonDriver, LiveUpdate, Result};
use jni::{
    Env, JavaVM, jni_sig, jni_str,
    objects::{Global, JClass, JObject, JString, JValue},
};
use std::{
    panic::catch_unwind,
    sync::{Mutex, PoisonError},
};

const BRIDGE_CLASS: &str = "io.github.gon.liveupdate.LiveUpdateBridge";
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
}

fn capture_android(
    env: &mut Env<'_>,
    raw_context: jni::sys::jobject,
) -> jni::errors::Result<(Global<JObject<'static>>, Global<JClass<'static>>)> {
    // SAFETY: ndk-context guarantees this non-null Context reference. The
    // initialization mutex ensures this temporary wrapper is unique here.
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
