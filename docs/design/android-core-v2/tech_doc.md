# Technical design

## Rust modules

- `model`: existing lifecycle model plus schema-version dispatch.
- `presentation`: cohesive Android presentation, icon, action, metric, progress, vendor, and event
  types with boundary validation.
- `driver`: default `drain_events` port and public event batch conversion.
- `bridge`: stable `events` command with request/reply size enforcement.
- `client`: serialized `events` operation under the same client lock.

Validation uses Unicode scalar limits for text, closed enums, finite/ranged numbers, unique action
IDs, ordered progress points, positive segment lengths, valid metric critical indexes, and safe
integer/timestamp bounds. Models are immutable values; updates replace complete snapshots.

## Android modules

- `AndroidAdapter`: storage/observation/post/cancel orchestration only.
- `NotificationRenderer`: pure-enough translation from checked JSON to `Notification.Builder` and
  API-specific system styles.
- `IconResolver`: resource lookup and app-private file decoding with bounds.
- `LiveUpdateActionReceiver` / `LiveUpdateDismissReceiver`: non-exported event producers.
- `EventStore`: bounded append and atomic drain.
- `SamsungNowBar`: closed, opt-in extra mapping isolated from standard rendering.

No AndroidX runtime dependency is required: compile SDK 37 exposes platform `ProgressStyle` and
`MetricStyle`, and runtime guards provide standard fallbacks. This keeps the standalone release AAR
self-contained.

## Test design

1. Rust unit tests enumerate every validation boundary and serde shape.
2. Rust integration tests prove v1 compatibility, v2 lifecycle replacement, bridge commands,
   default-driver events, and bounded failures.
3. Kotlin unit tests reject malformed JSON and test API-independent projection helpers.
4. Instrumentation tests inspect built notifications for every style/API branch, unique immutable
   actions, safe icon resolution, event draining, dismissal, vendor opt-in, and cleanup.
5. Native Rust instrumentation crosses JNI with non-ASCII text and a complete v2 presentation.
6. The S23 runner targets `R3CW30M9QSJ`, polls observable conditions instead of sleeping, captures
   instrumentation XML/logcat/dumpsys/screenshots, and removes only test-owned notifications/files.

Tests are written and observed failing before implementation. Coverage and mutation/spec mapping
remain release gates.

## Migration of the Tauri plugin

The plugin keeps its public TSX/React input and compiles the active layout to a v2 `Snapshot`. It
calls this crate for lifecycle and drains core events into the existing Tauri channel. Its Kotlin v2
`Lifecycle`, notification renderer, action receiver, dismissal store, image resolver, and Samsung
extra code are removed only after parity tests pass. iOS code and Tauri permission UI stay unchanged.
