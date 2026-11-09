# Android Live Activity core v2 PRD

## Problem

`liveupdate` currently owns a reliable Android lifecycle but exposes only a title, status, one
progress value, and a countdown. `tauri-plugin-live-activity` therefore duplicates lifecycle and
notification code in Kotlin to project React/TSX layouts, which splits correctness and leaves most
Android system presentation controls unavailable to native Rust users.

## Goal

Make this crate the Android-specific Live Activity core. A caller sends a resolved semantic
presentation; the crate validates and orders it, and the AAR renders it with Android system
notification styles. The Tauri plugin remains a compiler and transport adapter from TSX/React to
this contract.

## Required capabilities

- Preserve v1 progress/timer JSON and lifecycle behavior.
- Add a strict `schemaVersion: 2` presentation for Standard, BigText, Progress, Timer, and Metric
  system styles, with API-level fallback.
- Support status-chip text, rich progress segments/points/icons/semantics, up/down timers, up to
  three metrics, large icons, notification actions, content taps, and dismissal events.
- Expose detailed runtime capabilities so compilers can project deliberately rather than guess.
- Offer an explicit Samsung Now Bar extension without accepting arbitrary extras.
- Deliver bounded, durable action/dismiss event batches to Rust.
- Keep API 26 as the minimum and use API 36/37 features only behind runtime checks.
- Keep every cross-language request/reply bounded to one MiB and validate untrusted JSON at both
  Rust and Kotlin boundaries.

## Success criteria

- Existing v1 unit, integration, bridge, JNI, and device tests remain green.
- New contract behavior is specified and covered by Rust unit/integration tests, Kotlin unit tests,
  and API 37 instrumentation tests.
- Rust host line coverage remains at least 80%; Android coverage keeps the repository's existing
  stricter thresholds.
- A connected Galaxy S23 (`SM-S911N`) proves ProgressStyle, MetricStyle, actions, dismissals,
  images, promotion observation, update, and cleanup without relying on fixed sleeps.
- The Tauri plugin can replace its duplicated v2 Android lifecycle with a projection to this
  contract while retaining Tauri permission/UI and JavaScript event routing.

## Non-goals

- Arbitrary `RemoteViews` or pixel-identical React rendering. Android explicitly excludes custom
  content views from promoted Live Updates.
- Arbitrary broadcast/activity/service intents or arbitrary OEM extras from caller JSON.
- `CallStyle` in the first v2 slice. It needs person/call-state/full-screen policy that the current
  TSX model does not express; the tagged style contract leaves room for it later.
- iOS ActivityKit or Tauri command/channel ownership in this crate.
