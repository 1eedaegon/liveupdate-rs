# liveupdate-rs

Android Live Updates from Rust.

Android exposes promoted ongoing notifications through Java/Kotlin framework APIs, not a pure NDK
API. `liveupdate` keeps validation, idempotency, revision ordering, the durable lifecycle and the
public API in Rust, while a small Android AAR only talks to `NotificationManager` and
`SharedPreferences`.

## Install

```toml
[dependencies]
liveupdate = { version = "0.8.0", features = ["android"] }
```

Put `liveupdate-android-0.8.0.aar` from the [GitHub release](https://github.com/1eedaegon/liveupdate-rs/releases)
into `app/libs/`. The AAR is written in Kotlin and a file dependency brings no dependencies of its own, so
add the Kotlin standard library too, and in a watch app the library of its [Ongoing Activity](#wear-os):

```kotlin
dependencies {
    implementation(files("libs/liveupdate-android-0.8.0.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10") // or newer
    implementation("androidx.wear:wear-ongoing:1.1.0") // a watch app only
}
```

An icon that a presentation names, `IconSource::Resource("ic_order")`, is found by its name among the
drawables and then the mipmaps, and nothing in the application's code names it. With resource shrinking
(`isShrinkResources = true`) the build removes it, and a start that names it fails with `INVALID_INPUT`. Keep
such icons in `res/raw/keep.xml`:

```xml
<resources xmlns:tools="http://schemas.android.com/tools" tools:keep="@drawable/ic_order,@mipmap/ic_brand" />
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

#![deny(clippy::unwrap_used, clippy::expect_used)]

use liveupdate::{
    AndroidPresentation, Driver, EndPolicy, Fallback, IconSource, LiveUpdate, NotificationAction,
    PresentationStyle, ProgressPoint, ProgressSegment, ProgressStyle, Result, SamsungNowBar,
    SemanticStyle, Snapshot, StartRequest, Visibility,
};

/// Each value is built on its constructor, which takes what is required: a member that a later
/// version adds is then left as it is by default.
fn delivery_progress(progress: u32, icon: &IconSource) -> ProgressStyle {
    let segments = vec![
        ProgressSegment {
            id: Some(1),
            color: Some(0xff21_96f3),
            semantic_style: Some(SemanticStyle::Info),
            ..ProgressSegment::new(25)
        },
        ProgressSegment {
            id: Some(2),
            color: Some(0xff4c_af50),
            semantic_style: Some(SemanticStyle::Safe),
            ..ProgressSegment::new(75)
        },
    ];
    ProgressStyle {
        points: vec![ProgressPoint {
            id: Some(10),
            color: Some(0xffff_a000),
            semantic_style: Some(SemanticStyle::Caution),
            ..ProgressPoint::new(25)
        }],
        start_icon: Some(icon.clone()),
        end_icon: Some(icon.clone()),
        tracker_icon: Some(icon.clone()),
        ..ProgressStyle::new(progress, segments)
    }
}

/// Schema-v2 content for Android's rich progress presentation.
fn delivery_snapshot(revision: u64, progress: u32, status: &str) -> Snapshot {
    let icon = IconSource::Resource("liveupdate_status".into());
    let style = PresentationStyle::Progress(delivery_progress(progress, &icon));
    Snapshot {
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
        ..Snapshot::new(revision, "Mochi's snack delivery", status)
    }
}

/// Starts, updates and ends one delivery.
pub fn run_delivery<D: Driver>(updates: &LiveUpdate<D>) -> Result<()> {
    if !updates.capabilities()?.can_post() {
        return Ok(()); // Ask for the notification permission first.
    }
    let activity = updates.start(StartRequest::new(
        "delivery:order-123",
        Fallback::Notification,
        Visibility::Private,
        delivery_snapshot(0, 10, "Preparing"),
    ))?;

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
progress notification on older versions. `StartRequest::new` takes the schema version and the
template from the snapshot.
`Fallback::Notification` keeps a regular ongoing notification when promotion is unavailable;
`Fallback::None` returns `UNSUPPORTED_FEATURE` instead.

## Features

Every optional part is a member that is left out when unset. A constructor takes what is required
and sets none of them: `AndroidPresentation::new(style)`, `NotificationAction::new(id, title)`,
`StartRequest::new(key, fallback, visibility, snapshot)`, `Snapshot::new(revision, title, status)`,
and one for each style and its parts. So a caller names only what it uses, and a member that a later
version adds does not break the code:
`AndroidPresentation { open_url: Some(url), ..AndroidPresentation::new(style) }`.
`capabilities()?.presentation` says which of the optional parts the device and the AAR offer.

### Styles

| Style | `presentation.style` | Android shows |
| --- | --- | --- |
| Standard | `PresentationStyle::Standard` | The title and the status |
| Long text | `PresentationStyle::BigText(BigTextStyle::new(text))` | Up to 4,000 characters when expanded |
| Progress | `PresentationStyle::Progress(ProgressStyle::new(progress, segments))` | `ProgressStyle` with colored segments, points and start, end and tracker icons from API 36; a plain bar below |
| Timer | `PresentationStyle::Timer(TimerStyle::new(timer, counts_down))` | A clock in the header, down to the end or up from the start |
| Metrics | `PresentationStyle::Metric(MetricStyle::new(metrics))` | One to three typed values (number, text, stopwatch, countdown) from API 37; below it the numbers and the texts as lines under the status, and the first stopwatch or countdown as the clock in the header |

An update may carry another style than the start's (`Progress` while under way, `BigText` once
delivered); the record's `template` follows the latest snapshot. Schema v1 keeps its template.

### Options of a presentation

| Feature | Member of `AndroidPresentation` | Notes |
| --- | --- | --- |
| Status-chip text | `short_critical_text: Some("60%".into())` | At most 7 characters; shown on a promoted notification |
| Large icon | `large_icon: Some(IconSource::Resource("ic_order".into()))` | Or `IconSource::AppFile("icons/order.png".into())` under the app's files: at most 1 MiB and 1,024 pixels a side |
| Button that reports a tap | `actions: vec![NotificationAction::new("cancel", "Cancel")]` | Up to three; an `Action` event |
| Button that opens a screen | `NotificationAction { open_url: Some("myapp://orders/42/map".into()), ..NotificationAction::new("track", "Track") }` | No event; see [Links](#links) |
| Button that runs your service or receiver | `NotificationAction { target: Some(ActionTarget::new(TargetKind::ForegroundService, "com.example.RecorderService")), ..NotificationAction::new("record", "Record") }` | At once, with no screen; a `Handled` event; see [Action targets](#action-targets) |
| Button that asks to unlock first | `NotificationAction { authentication_required: true, ..NotificationAction::new("pay", "Pay") }` | Android asks from version 12. An older Android has no such setting and would send the tap from a locked device at once: there the AAR drops a tap while the device is locked, and the person taps again after unlocking |
| Tap that opens a screen | `open_url: Some("myapp://orders/42".into())` | The launch activity without it |
| Clock beside a style | `chronometer: Some(TimerStyle::new(timer, true))` | With any style but `Timer`: a progress bar and a countdown |
| Revision that alerts | `alert: true` | Posted on a second, high-importance channel; the next revision without it is quiet again |
| Lock-screen text | `public_version: Some(PublicVersion::new(title, status))` | What a secure lock screen shows instead; `Visibility::Private` only |
| Small icon | `small_icon: Some(IconSource::Resource("ic_brand".into()))` | A resource; the AAR's own icon without it |
| Accent color | `accent_color: Some(0xff21_96f3)` | ARGB |
| Header text | `sub_text: Some("Mochi Express".into())` | At most 64 characters |
| Samsung Now Bar | `samsung_now_bar: Some(SamsungNowBar { primary, secondary })` | One UI on API 37 and newer: its two lines take the place of the style there; see [Limits](#limits) |
| Not on a watch's face | `wear_ongoing_activity: false` | In a watch app, this activity stays in the tray; see [Wear OS](#wear-os) |

### The request and the lifecycle

| Feature | Use | Notes |
| --- | --- | --- |
| Idempotent start | `StartRequest::activity_key` | The same request answers the running activity; another one is a `CONFLICT` |
| Without promotion | `fallback: Fallback::Notification` or `Fallback::None` | A regular ongoing notification, or `UNSUPPORTED_FEATURE` |
| Lock screen | `visibility: Visibility::Public`, `Private` or `Secret` | `Private` hides the content on a secure lock screen unless it has a `public_version` |
| Foreground service | `placement: Placement::Foreground` | A service of the app holds the notification; see [Foreground service](#foreground-service) |
| Stale content | `Snapshot::stale_at` | The record reads `Stale` from that time |
| Start later | `start_at: Some(time)` | Stored now and begun at that time, also while the app does not run; see [Scheduled start](#scheduled-start) |
| End at once | `end(id, EndPolicy::Immediate)` | The notification is removed |
| Keep the last content | `end(id, EndPolicy::After { at })` | It stays, no longer ongoing, until `at`; update first to change it, end again with `Immediate` to remove it early |
| What the person did | `LiveUpdate::events()` | `Action`, `Dismissed` and `Opened` events, drained once |
| What happened to it | `list()` and every returned record | `state`, `promotion_state`, `degraded_features` |
| Each new state, once | `state_changes()` | `scheduled`, `active`, `stale`, `ended` and `dismissed`; see [State changes](#state-changes) |

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
| `NextCheck::At(time)` | The earliest stale time or start time ahead, in Unix milliseconds | Set a timer; a time that has passed means now |
| `NextCheck::Never` | Nothing is due | Wait for an operation, an event or the app's return to the foreground |

A host asks after its own `start` and `update`, at the time `next_check()` named, and when the app
returns to the foreground. A frozen process runs no timer: what it missed is answered at the next
call.

### Scheduled start

`StartRequest { start_at: Some(time), .. }` stores the activity now and begins it at `time`, in Unix
milliseconds. Nothing is shown before. At the time the AAR posts the notification from the stored
receipt, through an alarm and a receiver of its own, so the app does not have to run and no Rust is
loaded. A time that has passed begins at once, and the record is then that of an ordinary start,
never `Scheduled`. It needs schema version 2 and the tagged placement,
and `capabilities()?.presentation.scheduled_start`.

Until it begins the record reads `Scheduled` and names its `start_at`:

| While it waits | What happens |
| --- | --- |
| The same `start` again | Answers the same record |
| `update` | `CONFLICT`: it begins with the content of its start. End it and schedule again for other content |
| `end`, whatever its policy | Nothing is shown at the time |
| `state_changes()` | Answers `scheduled` once, and `active` once after it began |
| `next_check()` | Names the start time: an app that asks then has it begin on the dot, whatever the alarm does |

What happened while the app did not run is answered at its next call: an activity that began reads
`Active`, and one that the person removed in the meantime, or that could not be posted because
notifications were off by then, reads `Dismissed`. Set `alert: true` in the presentation for a start
that makes itself heard.

How close to its time it begins is Android's to say:

| The app | Alarm | Begins |
| --- | --- | --- |
| May set exact alarms: `presentation.exact_start` | Exact, also while the device dozes | At its time |
| May not, as most apps | Two inexact ones | Late by up to three quarters of the wait: ten minutes at most while the device is awake, an hour while it dozes |

The AAR declares no alarm permission. For the exact one, declare `SCHEDULE_EXACT_ALARM` in your
manifest and send the person to the "Alarms & reminders" setting: from Android 14 it is off until
they allow it. `USE_EXACT_ALARM` is for alarm and calendar apps only, by Google Play's policy, and
needs no setting. Android 12 does not know `USE_EXACT_ALARM`: an app that uses it also declares
`SCHEDULE_EXACT_ALARM` with `android:maxSdkVersion="32"`, which Android 12 allows without asking.
Without either, Android may also hold the alarm back for an app the person rarely opens. A start
that has to be on time then begins from a data message that wakes the app at that moment, with an
ordinary `start`.

Android drops alarms when the device restarts and when the person force-stops the app. The activity
then begins at the app's next lifecycle call, late. When the person takes the exact-alarm permission
away, Android drops the exact alarm and stops the app: the start then begins by the inexact alarm
that is always set beside it. To have the alarms set again after a restart, declare the AAR's
receiver for it in your manifest:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<receiver
    android:name="io.github.eedaegon.liveupdate.LiveUpdateStartReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

Android asks that a Live Update appears only when its activity is imminent, and mostly for
activities the person started: schedule the start of something they asked for, not a reminder.

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

### Action targets

A button can run your own code at once, without a screen: a recorder's Record, a player's Pause. Its
tap goes to a service or a receiver of your application, which Android starts or reaches directly,
also with the screen off, from the lock screen and while no process of the app runs. It is the way
an iPhone's Live Activity runs an App Intent. `examples/service.rs`, which compiles for Android:

```rust
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
```

- `kind` is `Service`, `ForegroundService` (a service that may not run yet; it must call
  `startForeground` within seconds) or `Broadcast`.
- `component` is the class name, ASCII, in any Java package of your application: the component is
  always in your own package, so a Tauri app whose classes are in another Java package than its
  application id names them as they are. `action`, the intent's action, is optional.
- The component must not be exported, since any app could then send it what looks like a tap, and
  must run in the application's main process, whose event store `take` writes. A post that names a
  component your application does not have as the kind says, an exported one or one in another
  process fails with `INVALID_INPUT`, and so does an update of an activity whose target an update of
  the app removed: name another target, or none. A target beside `open_url` is refused.
- The capability `presentation.action_targets` says the AAR delivers targets; without it, an earlier
  AAR, a start or an update that names one is `UNSUPPORTED_FEATURE`.
- `LiveUpdate::android_from_raw(vm, context)` builds a client in the service's own JNI entry, with no
  activity of the app and without `ndk-context`. The call blocks: make it off the main thread.

The service takes the tap first, with `LiveUpdateActions.take`, and is in the foreground at once,
with one notification, through `LiveUpdateForeground.attachOr`:

```kotlin
class RecorderService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Within Android's few seconds: the activity's notification, or this service's own.
        LiveUpdateForeground.attachOr(this, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, OWN_ID) { own() }
        val tap = LiveUpdateActions.take(this, intent)
        if (tap != null && !tap.withheld) {
            recording = !recording
            thread {
                // Rust starts or updates the activity; `attach` then puts it in place of this service's own.
                if (Native.showRecording(applicationContext, recording)) {
                    LiveUpdateForeground.attach(this, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

- `take` answers the activity's and the action's ids, or null for an intent that is no such tap. It
  records the tap as a `Handled` event, which your Rust drains with the others and must not act on
  again: the application acted already. It never throws, and it removes the marks of the intent, so
  an intent delivered twice is one tap.
- `withheld` is true only below Android 12, for an action with `authentication_required` while the
  device is locked: Android sent that tap without asking for the unlock. From Android 12 it asks.
- `take` reads the stored receipts and the event queue on the calling thread and writes the event
  without waiting for the disk: about 150 bytes, in the queue of at most 128 events that `events()`
  drains. With many large receipts, call it off the main thread.
- With `attachOr` the service is never without a notification. A start that fails, `SYSTEM_LIMIT` or
  `UNSUPPORTED_FEATURE`, leaves its own in place. When the activity ends, its own comes back and the
  service stays in the foreground, as a recording service must; the listener of `setListener` is
  told. A service whose activity another service took shows its own again too. `detach` forgets it.
- The builder runs on each `attachOr` and when the activity ends, outside the library's lock, so it
  can say what the service does then. Its id may not be `LiveUpdateForeground.NOTIFICATION_ID`.

Native code can watch every event without taking it from the queue that Rust drains:
`LiveUpdateEvents.addNativeListener { event -> }` is told of each stored event with its content
(`kind`, `activityId`, `actionId`), on the thread that stored it, beside the one listener of
`setListener`.

### Wear OS

In a watch app the same calls put the activity on the watch face, as an icon that opens the app:

1. Add `androidx.wear:wear-ongoing` to the watch app, as in [Install](#install). The AAR uses it when
   it is there and does not bring it.
2. Build your Rust library for `armeabi-v7a` too (`armv7-linux-androideabi`): a watch such as the Galaxy
   Watch4 runs 32-bit code only.
3. Start and update as on a phone. The icon, the title, the status and a header clock are the
   notification's, and a tap opens what a tap on the notification opens.

`capabilities()?.presentation.wear_ongoing_activity` is true where the watch shows it, and a presentation
with `wear_ongoing_activity: false` keeps its activity off the watch face. An update may turn it off
or on; a notification that a foreground service holds keeps its icon until the activity ends.

For Wear OS 3 to 6 (seen on 5): older watches ignore it, Wear OS 7 shows Live Updates itself, and a
phone's activity stays on the phone.

## Versions

The crate and the AAR of a release have one version and are tested as a pair. They also work apart:

| Crate | AAR | Together |
| --- | --- | --- |
| Later | 0.7.0 or later | The activity is posted. A member that the AAR does not know is not shown, and its capability reads `false` |
| Later | Up to 0.6.0 | A request with a member that the AAR does not know fails with `INVALID_INPUT`; one without works |
| Earlier | Later | It works: the crate reads what it knows of a reply. From 0.7.0 an event of a kind it does not know is `EventKind::Unknown`; before, the batch with it was lost |

A member that an activity depends on, a start time or the foreground placement, is not left out
silently: the crate refuses the request with `UNSUPPORTED_FEATURE` where the AAR does not report its
capability.

- A request that the application gives as JSON is read strictly in every version: a member that the
  crate does not know is a mistake where it was written, and is refused.
- A crate reads the receipts of every earlier version. Going back to an earlier crate is another
  matter: it does not read a receipt that has a member of the later one, and fails with
  `NATIVE_ERROR` until the application's data is cleared.
- A later version adds members to the structs and variants to the enums. Neither breaks Rust code
  that builds its values on the constructors, and matches every enum of the crate with a `_` arm.

### Android versions

The AAR runs from Android 8 (API 26). What a later Android adds is shown there, and before it the
nearest thing that Android has:

| Shown as | From | Before it |
| --- | --- | --- |
| A promoted Live Update | API 36 | An ongoing notification (`Fallback::Notification`) |
| `ProgressStyle`: segments, points, icons | API 36 | A plain progress bar |
| Status-chip text | API 36 | Not shown |
| `MetricStyle` | API 37 | Lines of text under the status, and the first stopwatch or countdown as the clock in the header |
| Semantic colors of a segment, a point and a metric | API 37 | Not shown |
| Samsung Now Bar | API 37, One UI | Not shown |
| Unlock before a button's tap | API 31 | The AAR drops a tap while the device is locked |

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
tombstones are retained, the oldest tombstone going first. A start or an update stores them in at most
1,000,000 bytes: older tombstones go for the room it needs, sooner than the 128th when the content is
large, and it fails with `SYSTEM_LIMIT` when the live activities alone leave none. Malformed or
oversized adapter data fails closed as `NATIVE_ERROR`.

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
- `ProgressStyle::new` styles the bar by its progress, as Android does by default. With
  `styled_by_progress: false` every segment is shown in full and only a tracker icon shows where the
  progress is: without one, 0 and 100 look the same.
- What One UI 9 made of the options on a Galaxy S23 on 2026-11-19 ([docs/testing.md](docs/testing.md)):
  a promoted activity is in the Now Bar without `samsung_now_bar`, with its progress bar once it is
  opened, and with it the Now Bar and the card show its two lines alone, with the application's icon
  and no progress bar. A point at the track's full length is not drawn. The status chip shows the
  first metric, whatever `critical_metric` names. Of three metrics a value longer than about seven
  characters is cut, a number too. A right-to-left layout is the system's: the library passes no
  direction.
- On a watch the icon on the watch face is all an activity shows: Wear OS does not list an ongoing
  notification among its notifications, and a revision that alerts does not pop up there.
- From Android 15 a clock in the header, of a `Timer` style, a `chronometer` or a schema-v1 timer,
  cannot be more than 14 days old: the system drops such a post without an error, so the adapter
  refuses it with `INVALID_INPUT`. The age is counted from the end of a countdown and from the start
  of a clock that counts up. An activity that runs longer leaves the clock out, or from API 37 shows
  the time as a stopwatch metric, which has no such limit.
- An update needs the app's process: Android has no push that updates a notification. In the
  background, use a foreground service or a data message that wakes the app. A start does not:
  `start_at` begins an activity while the app does not run.
- A scheduled start is on the minute only with the exact-alarm permission: `SCHEDULE_EXACT_ALARM`,
  which from Android 14 the person has to allow, or `USE_EXACT_ALARM`. Without it Android chooses the
  moment, up to ten minutes late while the device is awake and up to an hour while it dozes. It cannot
  be updated before it begins, and it does not take the foreground placement.
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

`tests/android/consumer.sh` builds an application as a host does, from a published version or from this
checkout, with R8 and resource shrinking, and runs it on one device.
[docs/testing.md](docs/testing.md) describes the emulator suite with Kotlin and Rust coverage and the
CI jobs; [CHANGELOG.md](CHANGELOG.md) lists the changes of each version.

## License

[MIT](LICENSE-MIT) or [Apache-2.0](LICENSE-APACHE), at your option.
