# liveupdate-rs

Android Live Updates from Rust.

Android exposes promoted ongoing notifications through Java/Kotlin framework APIs, not a pure NDK
API. `liveupdate` keeps validation, idempotency, revision ordering, the durable lifecycle and the
public API in Rust, while a small Android AAR only talks to `NotificationManager` and
`SharedPreferences`.

## Install

```toml
[dependencies]
liveupdate = { version = "0.1.3", features = ["android"] }
```

Put `liveupdate-android-0.1.3.aar` from the [GitHub release](https://github.com/1eedaegon/liveupdate-rs/releases)
into `app/libs/`. The AAR is written in Kotlin and a file dependency brings no dependencies of its own, so
add the Kotlin standard library too:

```kotlin
dependencies {
    implementation(files("libs/liveupdate-android-0.1.3.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10") // or newer
}
```

The AAR merges `POST_NOTIFICATIONS` and `POST_PROMOTED_NOTIFICATIONS` into the application
manifest. Your app still has to request the runtime notification permission on Android 13 and
newer. The Rust Android runtime must initialize
[`ndk-context`](https://crates.io/crates/ndk-context); common NativeActivity and Rust window
runtimes already do this before application code runs. Before that, `LiveUpdate::android()` returns
`NATIVE_ERROR`, as long as the build unwinds on panic (Rust's default): with `panic = "abort"`, the
process aborts.

The Android namespace is `io.github.eedaegon.liveupdate` because Android package segments cannot
start with a digit. The repository, Cargo author and project owner remain `1eedaegon`.

## Use

Create one `LiveUpdate` client with `LiveUpdate::android()` during application startup and keep it
in application state. This is [examples/delivery.rs](examples/delivery.rs), which CI compiles for
Android:

```rust
//! One delivery from start to end. `run_delivery` works with any `Driver`, so tests can pass a fake
//! one; on Android, `on_android` hands it the client of the companion AAR.

use liveupdate::{
    AndroidPresentation, Driver, EndPolicy, Fallback, IconSource, LiveUpdate, NotificationAction,
    PresentationStyle, ProgressPoint, ProgressSegment, ProgressStyle, Result, SamsungNowBar,
    SemanticStyle, Snapshot, StartRequest, Template, Visibility,
};

fn delivery_progress(progress: u32, icon: &IconSource) -> ProgressStyle {
    ProgressStyle {
        progress,
        indeterminate: false,
        styled_by_progress: true,
        segments: vec![
            ProgressSegment {
                length: 25,
                id: Some(1),
                color: Some(0xff21_96f3),
                semantic_style: Some(SemanticStyle::Info),
            },
            ProgressSegment {
                length: 75,
                id: Some(2),
                color: Some(0xff4c_af50),
                semantic_style: Some(SemanticStyle::Safe),
            },
        ],
        points: vec![ProgressPoint {
            position: 25,
            id: Some(10),
            color: Some(0xffff_a000),
            semantic_style: Some(SemanticStyle::Caution),
        }],
        start_icon: Some(icon.clone()),
        end_icon: Some(icon.clone()),
        tracker_icon: Some(icon.clone()),
    }
}

/// Schema-v2 content for Android's rich progress presentation.
fn delivery_snapshot(revision: u64, progress: u32, status: &str) -> Snapshot {
    let icon = IconSource::Resource("liveupdate_status".into());
    Snapshot {
        revision,
        title: "Mochi's snack delivery".into(),
        status: status.into(),
        progress: None,
        timer: None,
        stale_at: None,
        presentation: Some(AndroidPresentation {
            style: PresentationStyle::Progress(delivery_progress(progress, &icon)),
            short_critical_text: Some(format!("{progress}%")),
            large_icon: Some(icon.clone()),
            actions: vec![NotificationAction {
                id: "track".into(),
                title: "Track".into(),
                icon: Some(icon),
                authentication_required: false,
            }],
            samsung_now_bar: Some(SamsungNowBar {
                primary: Some("Mochi delivery".into()),
                secondary: Some(status.into()),
            }),
        }),
    }
}

/// Starts, updates and ends one delivery.
pub fn run_delivery<D: Driver>(updates: &LiveUpdate<D>) -> Result<()> {
    if !updates.capabilities()?.can_post() {
        return Ok(()); // Ask for the notification permission first.
    }
    let activity = updates.start(StartRequest {
        activity_key: "delivery:order-123".into(),
        schema_version: 2,
        template: Template::Progress,
        fallback: Fallback::Notification,
        visibility: Visibility::Private,
        snapshot: delivery_snapshot(0, 10, "Preparing"),
    })?;

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
```

`activity_key` makes `start` idempotent. A repeated start with the same request returns the same
handle; incompatible content returns `CONFLICT`. Updates must keep the original template and use a
monotonically increasing `revision`. The returned record tells you whether Android observed the
notification and whether promoted presentation actually happened.

Schema v2 keeps `Snapshot::progress` and `Snapshot::timer` empty and puts typed Android UI under
`presentation`. The example becomes a system `ProgressStyle` on API 36 and newer and a classic
progress notification on older versions. Change both `template` and `presentation.style` together
for Standard, BigText, Timer or Metric. `Fallback::Notification` keeps a regular ongoing
notification when promotion is unavailable; `Fallback::None` returns `UNSUPPORTED_FEATURE`
instead. Action taps and dismissals are drained through `LiveUpdate::events()`.

## How it works

```text
Rust application
    |
    | start / update / end / list / events
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
    +---- action / dismissal event queue ------> Rust event drain
```

Rust writes the receipt before posting. If the process or framework call fails, the next identical
operation retries safely. `list` reconciles receipts with Android's active notifications, so a
notification removed by the user becomes `Dismissed`. At most 32 active records and 128 terminal
tombstones are retained, the oldest tombstone going first; malformed or oversized adapter data fails
closed as `NATIVE_ERROR`.

[docs/spec.md](docs/spec.md) lists every requirement with the tests that prove it, and
[docs/testing.md](docs/testing.md) says how to run them.

## Limits

- Promoted ongoing presentation requires Android API 36 or newer, enabled notifications, enabled
  promotion settings and OEM system-UI support. A successful post does not guarantee promotion.
- A Galaxy S23 (`SM-S911N`) on Android 17 / One UI 9 has passed the API 37 ProgressStyle,
  MetricStyle, Samsung Now Bar, action, dismissal, image, Rust/JNI and lifecycle suite. Promotion
  still depends on the user's setting and Samsung's system policy; a structurally promotable post
  can remain a regular ongoing notification.
- Schema v2 supports Standard, BigText, rich Progress, Timer and Metric system styles, resource and
  app-private icons, up to three immutable actions, and durable action/dismissal events through
  `LiveUpdate::events()`. `LiveUpdateEvents.setListener` in the AAR tells the app as soon as an event is stored,
  so it can drain without polling. It deliberately does not accept arbitrary `RemoteViews`, intents, paths or
  OEM extras.
- Android removes notifications immediately. `EndPolicy::After` returns `UNSUPPORTED_FEATURE`.
- Visibility is reported as `Unknown`: requesting private notification content does not prove what
  an OEM system UI actually revealed.
- Use one retained client per process so operations share the same serialization boundary. Every
  call blocks its thread for a storage write and a framework call, so keep it off the UI thread.

## Development

The reproducible shell pins Rust 1.96, JDK 21, Gradle and `cargo-ndk`. The environment check
requires Android SDK 37 and NDK 27.3.13750724:

```sh
nix develop
./scripts/check-android-env.sh
cargo test --locked --all-targets
cargo ndk -t arm64-v8a -t x86_64 check --locked --features android
gradle :android:testDebugUnitTest :android:lintDebug :android:assembleRelease --no-daemon
sh tools/spec-coverage.sh
```

[docs/testing.md](docs/testing.md) describes the emulator suite with Kotlin and Rust coverage and the
CI jobs; [CHANGELOG.md](CHANGELOG.md) lists the changes of each version.

## License

[MIT](LICENSE-MIT) or [Apache-2.0](LICENSE-APACHE), at your option.
