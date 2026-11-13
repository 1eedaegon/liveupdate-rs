# liveupdate-rs

Android Live Updates from Rust.

Android exposes promoted ongoing notifications through Java/Kotlin framework APIs, not a pure NDK
API. `liveupdate` keeps validation, idempotency, revision ordering, the durable lifecycle and the
public API in Rust, while a small Android AAR only talks to `NotificationManager` and
`SharedPreferences`.

## Install

```toml
[dependencies]
liveupdate = { version = "0.3.0", features = ["android"] }
```

Put `liveupdate-android-0.3.0.aar` from the [GitHub release](https://github.com/1eedaegon/liveupdate-rs/releases)
into `app/libs/`. The AAR is written in Kotlin and a file dependency brings no dependencies of its own, so
add the Kotlin standard library too:

```kotlin
dependencies {
    implementation(files("libs/liveupdate-android-0.3.0.aar"))
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

The Android namespace is `io.github.eedaegon.liveupdate`: an Android package segment cannot start with
a digit, so the owner's name (github.com/1eedaegon) loses its first character there, and in the JNI
names made from it. Names that are plain strings keep it whole, under
`io.github.1eedaegon.liveupdate`: the preferences file, the notification's record key, and the actions
and extras of the library's intents. Versions 0.2.0 and 0.2.1 named all of them
`io.github.gon.liveupdate`.

## Use

Create one `LiveUpdate` client with `LiveUpdate::android()` during application startup and keep it
in application state. This is [examples/delivery.rs](examples/delivery.rs), which CI compiles for
Android:

```rust
//! One delivery from start to end. `run_delivery` works with any `Driver`, so tests can pass a fake
//! one; on Android, `on_android` hands it the client of the companion AAR.

use liveupdate::{
    AndroidPresentation, Driver, EndPolicy, Fallback, IconSource, LiveUpdate, NotificationAction,
    Placement, PresentationStyle, ProgressPoint, ProgressSegment, ProgressStyle, Result,
    SamsungNowBar, SemanticStyle, Snapshot, StartRequest, Template, Visibility,
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
    let style = PresentationStyle::Progress(delivery_progress(progress, &icon));
    Snapshot {
        revision,
        title: "Mochi's snack delivery".into(),
        status: status.into(),
        progress: None,
        timer: None,
        stale_at: None,
        presentation: Some(AndroidPresentation {
            short_critical_text: Some(format!("{progress}%")),
            large_icon: Some(icon.clone()),
            actions: vec![NotificationAction {
                icon: Some(icon),
                ..NotificationAction::new("track", "Track")
            }],
            samsung_now_bar: Some(SamsungNowBar {
                primary: Some("Mochi delivery".into()),
                secondary: Some(status.into()),
            }),
            ..AndroidPresentation::new(style)
        }),
    }
}

/// Starts, updates and ends one delivery.
pub fn run_delivery<D: Driver>(updates: &LiveUpdate<D>) -> Result<()> {
    if !updates.capabilities()?.can_post() {
        return Ok(()); // Ask for the notification permission first.
    }
    let activity = updates.start(StartRequest {
        placement: Placement::Tagged,
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
handle; incompatible content returns `CONFLICT`. Updates use a monotonically increasing `revision`.
The returned record tells you whether Android observed the notification and whether promoted
presentation actually happened.

Schema v2 keeps `Snapshot::progress` and `Snapshot::timer` empty and puts typed Android UI under
`presentation`. The example becomes a system `ProgressStyle` on API 36 and newer and a classic
progress notification on older versions. A start names the `template` of its `presentation.style`.
`Fallback::Notification` keeps a regular ongoing notification when promotion is unavailable;
`Fallback::None` returns `UNSUPPORTED_FEATURE` instead.

## Features

Every optional part is a member that is left out when unset. `AndroidPresentation::new(style)` and
`NotificationAction::new(id, title)` set none of them, so a caller names only what it uses:
`AndroidPresentation { open_url: Some(url), ..AndroidPresentation::new(style) }`.
`capabilities()?.presentation` says which of them the device and the AAR offer.

### Styles

| Style | `presentation.style` | Android shows |
| --- | --- | --- |
| Standard | `PresentationStyle::Standard` | The title and the status |
| Long text | `PresentationStyle::BigText(BigTextStyle { text, summary })` | Up to 4,000 characters when expanded |
| Progress | `PresentationStyle::Progress(ProgressStyle { progress, segments, points, .. })` | `ProgressStyle` with colored segments, points and start, end and tracker icons from API 36; a plain bar below |
| Timer | `PresentationStyle::Timer(TimerStyle { timer, counts_down })` | A clock in the header, down to the end or up from the start |
| Metrics | `PresentationStyle::Metric(MetricStyle { metrics, critical_metric })` | One to three typed values (number, text, stopwatch, countdown) from API 37; the title and the status below |

An update may carry another style than the start's (`Progress` while under way, `BigText` once
delivered); the record's `template` follows the latest snapshot. Schema v1 keeps its template.

### Options of a presentation

| Feature | Member of `AndroidPresentation` | Notes |
| --- | --- | --- |
| Status-chip text | `short_critical_text: Some("60%".into())` | At most 7 characters; shown on a promoted notification |
| Large icon | `large_icon: Some(IconSource::Resource("ic_order".into()))` | Or `IconSource::AppFile("icons/order.png".into())` under the app's files: at most 1 MiB and 1,024 pixels a side |
| Button that reports a tap | `actions: vec![NotificationAction::new("cancel", "Cancel")]` | Up to three; an `Action` event |
| Button that opens a screen | `NotificationAction { open_url: Some("myapp://orders/42/map".into()), ..NotificationAction::new("track", "Track") }` | No event; see [Links](#links) |
| Tap that opens a screen | `open_url: Some("myapp://orders/42".into())` | The launch activity without it |
| Clock beside a style | `chronometer: Some(TimerStyle { timer, counts_down: true })` | With any style but `Timer`: a progress bar and a countdown |
| Revision that alerts | `alert: true` | Posted on a second, high-importance channel; the next revision without it is quiet again |
| Lock-screen text | `public_version: Some(PublicVersion { title, status })` | What a secure lock screen shows instead; `Visibility::Private` only |
| Small icon | `small_icon: Some(IconSource::Resource("ic_brand".into()))` | A resource; the AAR's own icon without it |
| Accent color | `accent_color: Some(0xff21_96f3)` | ARGB |
| Header text | `sub_text: Some("Mochi Express".into())` | At most 64 characters |
| Samsung Now Bar | `samsung_now_bar: Some(SamsungNowBar { primary, secondary })` | One UI on API 37 and newer |

### The request and the lifecycle

| Feature | Use | Notes |
| --- | --- | --- |
| Idempotent start | `StartRequest::activity_key` | The same request answers the running activity; another one is a `CONFLICT` |
| Without promotion | `fallback: Fallback::Notification` or `Fallback::None` | A regular ongoing notification, or `UNSUPPORTED_FEATURE` |
| Lock screen | `visibility: Visibility::Public`, `Private` or `Secret` | `Private` hides the content on a secure lock screen unless it has a `public_version` |
| Foreground service | `placement: Placement::Foreground` | A service of the app holds the notification; see [Foreground service](#foreground-service) |
| Stale content | `Snapshot::stale_at` | The record reads `Stale` from that time |
| End at once | `end(id, EndPolicy::Immediate)` | The notification is removed |
| Keep the last content | `end(id, EndPolicy::After { at })` | It stays, no longer ongoing, until `at`; update first to change it, end again with `Immediate` to remove it early |
| What the person did | `LiveUpdate::events()` | `Action`, `Dismissed` and `Opened` events, drained once |
| What happened to it | `list()` and every returned record | `state`, `promotion_state`, `degraded_features` |
| Each new state, once | `state_changes()` | `active`, `stale`, `ended` and `dismissed`; see [State changes](#state-changes) |

### State changes

Android tells nobody when a notification's stale time passes, and the crate has no timer.
`state_changes()` reconciles and answers the activities whose state is another than the one it last
answered for them, each once: `active` once Android lists the activity, `stale` once its `stale_at`
has passed, `active` again after an update, `ended`, `dismissed`. What it answered is stored with the
receipts, so the answers continue after a restart, and `unknown` is never one of them.

`next_check()` reads the receipts and says when to call it again:

| `next_check()` | Meaning | What a host does |
| --- | --- | --- |
| `NextCheck::Now` | A state is known and was not answered yet | Ask now |
| `NextCheck::Soon` | A post is not listed by Android yet | Ask in about half a second, then a little later each time, and give up after a few |
| `NextCheck::At(time)` | The earliest stale time ahead, in Unix milliseconds | Set a timer; a time that has passed means now |
| `NextCheck::Never` | Nothing is due | Wait for an operation, an event or the app's return to the foreground |

A host asks after its own `start` and `update`, at the time `next_check()` named, and when the app
returns to the foreground. A frozen process runs no timer: what it missed is answered at the next
call.

### Links

A link is an absolute URL of printable ASCII with a lowercase scheme, at most 2,048 characters. It is
opened with `ACTION_VIEW` inside your own package only, so one of your activities, exported or not,
must have an intent filter for it; a URL nothing opens fails the post. `intent:`, `content:`, `file:`, `javascript:`,
`data:` and `android-app:` are refused.

The opening intent carries the extras `io.github.1eedaegon.liveupdate.activityId` and, for a button,
`io.github.1eedaegon.liveupdate.actionId`. Hand the intent back to get an `Opened` event:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState == null) LiveUpdateEvents.opened(this, intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    LiveUpdateEvents.opened(this, intent)
}
```

`opened` counts an intent once, only for an activity this library stored, and writes a small
preferences file on the calling thread.

### Foreground service

Android shows one notification for a foreground service. Start the activity with
`Placement::Foreground` and let your service hold its notification instead of posting a second one.
The service, its type and its permission are yours; the AAR declares none. One live activity has the
placement at a time.

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<service android:name=".RideService" android:exported="false" android:foregroundServiceType="location" />
```

```kotlin
class RideService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // After `start` returned for the activity: its notification becomes this service's.
        if (!LiveUpdateForeground.attach(this, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)) stopSelf()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// Once, for example in Application.onCreate: `end` takes the service out of the foreground.
LiveUpdateForeground.setListener { stopService(Intent(this, RideService::class.java)) }
```

Start the activity first and the service second: a service that Android started in the foreground
must show a notification within seconds, and `attach` answers false without a live activity. `update`
keeps the notification the service's, `end` releases it, and `LiveUpdateForeground.detach(service)`,
which a service calls before it stops for another reason, leaves it as an ordinary notification. Do
not end the activity in the same instant as a detach: Android may post the notification once more. A process that Android killed loses the notification; a
restarted service that calls `attach` before the first lifecycle call shows the activity again.

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
operation retries safely. A post that the adapter refuses as invalid, such as a URL no activity
opens or an icon the app does not have, leaves no receipt: correct the request and send it again. `list` reconciles receipts with Android's active notifications, so a
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
  OEM extras: a link is a URL that the app's own package opens.
- An end removes the notification at once. `EndPolicy::After` keeps the last content until its time,
  as an ordinary notification that the person can remove; it is no longer promoted.
- An update needs the app's process: Android has no push that updates a notification. In the
  background, use a foreground service or a data message that wakes the app.
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
