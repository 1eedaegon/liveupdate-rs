# liveupdate-rs

Android Live Updates from Rust.

Android exposes promoted ongoing notifications through Java/Kotlin framework APIs, not a pure NDK
API. `liveupdate` keeps validation, idempotency, revision ordering, durable lifecycle and the public
API in Rust, while a small Android AAR only talks to `NotificationManager` and
`SharedPreferences`.

## Install

Add the Rust crate from GitHub while the first crate release is being prepared:

```toml
[target.'cfg(target_os = "android")'.dependencies]
liveupdate = { git = "https://github.com/1eedaegon/liveupdate-rs", features = ["android"] }
```

Build the companion AAR from the same revision:

```sh
git clone https://github.com/1eedaegon/liveupdate-rs
cd liveupdate-rs
nix develop -c gradle :android:assembleRelease --no-daemon
```

Copy `android/build/outputs/aar/android-release.aar` into your Android app's `app/libs` directory,
then include it from `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("libs/android-release.aar"))
}
```

The AAR merges `POST_NOTIFICATIONS` and `POST_PROMOTED_NOTIFICATIONS` into the application
manifest. Your app still has to request the runtime notification permission on Android 13 and
newer. The Rust Android runtime must initialize
[`ndk-context`](https://crates.io/crates/ndk-context); common NativeActivity and Rust window
runtimes already do this before application code runs.

The Android namespace is `io.github.eedaegon.liveupdate` because Android package segments cannot
start with a digit. The repository, Cargo author and project owner remain `1eedaegon`.

## Use

Create one `LiveUpdate` client during Android application startup and retain it in application
state:

```rust
use liveupdate::{
    EndPolicy, Fallback, LiveUpdate, Result, Snapshot, StartRequest, Template, Visibility,
};

fn run_delivery(updates: &LiveUpdate<liveupdate::AndroidDriver>) -> Result<()> {
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
        },
    )?;

    updates.end(&activity.id, EndPolicy::Immediate)?;
    Ok(())
}

// Call this after Android has initialized ndk-context.
let updates = LiveUpdate::android()?;
let capabilities = updates.capabilities()?;
if capabilities.can_post() {
    run_delivery(&updates)?;
}
# Ok::<(), liveupdate::Error>(())
```

`activity_key` makes `start` idempotent. A repeated start with the same request returns the same
handle; incompatible content returns `CONFLICT`. Updates must keep the original template and use a
monotonically increasing `revision`. The returned record tells you whether Android observed the
notification and whether promoted presentation actually happened.

Use `Template::Timer` with a `Timer { started_at, ends_at }` instead of `progress` for countdowns.
`Fallback::Notification` keeps a regular ongoing notification when promotion is unavailable;
`Fallback::None` returns `UNSUPPORTED_FEATURE` instead.

## How it works

```text
Rust application
    |
    | start / update / end / list
    v
liveupdate Rust lifecycle
    | validates input and revisions
    | writes a retryable durable receipt
    v
bounded JSON + JNI bridge
    v
liveupdate Android AAR
    | SharedPreferences + NotificationManager
    v
Android system notification / promoted ongoing UI
    |
    +---- active notification observation ----> Rust reconciliation
```

Rust writes the receipt before posting. If the process or framework call fails, the next identical
operation retries safely. `list` reconciles receipts with Android's active notifications, so a
notification removed by the user becomes `Dismissed`. At most 32 active records and 128 terminal
tombstones are retained; malformed or oversized adapter data fails closed as `NATIVE_ERROR`.

## Limits

- Promoted ongoing presentation requires Android API 36 or newer, enabled notifications, enabled
  promotion settings and OEM system-UI support. A successful post does not guarantee promotion.
- Galaxy S23 is currently a fallback compatibility target. Firmware that does not expose promoted
  ongoing notifications receives the regular ongoing notification only when
  `Fallback::Notification` is selected.
- The AAR currently supports the system progress and countdown templates. Custom layouts, actions
  and event streams are not part of this alpha API.
- Android removes notifications immediately. `EndPolicy::After` returns `UNSUPPORTED_FEATURE`.
- Visibility is reported as `Unknown`: requesting private notification content does not prove what
  an OEM system UI actually revealed.
- Use one retained client per process so operations share the same serialization boundary.

## Development

The reproducible shell pins Rust 1.96, JDK 21, Gradle and `cargo-ndk`. The environment check
requires Android SDK 37 and NDK 27.3.13750724:

```sh
nix develop
./scripts/check-android-env.sh
cargo test --locked --all-targets
cargo ndk -t arm64-v8a -t x86_64 check --locked --features android
gradle :android:testDebugUnitTest :android:lintDebug :android:assembleRelease --no-daemon
# With an API 37 emulator or device connected:
gradle :android:coverageVerification --no-daemon
```

See the [architecture](docs/implementation/architecture.md),
[Android test matrix](docs/testing.md) and [implementation task list](docs/implementation/task_list.md).

## License

[MIT](LICENSE-MIT) or [Apache-2.0](LICENSE-APACHE), at your option.
