# Changelog

## Unreleased

## 0.1.2 - 2026-11-10

- Added `LiveUpdateEvents.setListener` to the AAR: the action and dismiss receivers call the app's one listener once
  each event is stored, so the app drains `LiveUpdate::events()` at once instead of polling. A listener that throws is
  logged and the event stays queued.

## 0.1.1 - 2026-11-10

- Breaking pre-1.0 cleanup: removed the redundant `Backend` enum and `ActivityRecord.backend` field.
  This crate is an Android core; cross-platform adapters add their own backend discriminator at
  their public boundary.

## 0.1.0 - 2026-11-09

- Added schema v2 Android presentations for Standard, BigText, ProgressStyle, Timer and MetricStyle,
  with strict validation and lower-API fallbacks while retaining schema v1 JSON compatibility.
- Added safe resource/app-private icons, immutable notification actions, a bounded durable
  action/dismissal event queue, `LiveUpdate::events()`, detailed presentation capabilities and an
  explicit Samsung Now Bar mapping.
- Added Rust contract/integration tests and API 37 instrumentation coverage, including a connected
  Galaxy S23 run through Rust, JNI and the Android framework.

## 0.1.0-alpha.2

Fixes found while writing the specification and its tests:

- Ending an activity while 128 tombstones were kept evicted the record it had just ended, so a second
  `end` answered `NOT_FOUND`. The oldest tombstone now goes first.
- A cancelled notification that Android still listed (its cancel is asynchronous) made the 32nd live
  activity fail with `NATIVE_ERROR`. Observations are now bounded at 160, as the adapter bounds them.
- A driver that panicked left the client's lock poisoned, and every later call answered
  `NATIVE_ERROR`. The client now takes the lock over.
- `LiveUpdate::android()` panicked when `ndk-context` was not initialized yet, and that panic made
  every later call fail. It now returns `NATIVE_ERROR`, and works once the host has initialized
  `ndk-context`.

Tests, CI and docs:

- `docs/spec.md`: every requirement with an ID and the tests that prove it, checked by
  `tools/spec-coverage.sh`.
- Unit tests in the modules, more integration tests, Kotlin unit tests, and instrumentation tests for
  every adapter and JNI path, run with Kotlin and Rust coverage on an API 37 emulator by
  `tests/android/run.sh`.
- CI runs only what a change reaches, caches the toolchains, the SDK packages and the system image,
  and reuses a pull request's emulator pass on main. CI's emulator ran API 36 until now: `android
  emulator create` picks its own system image. It now runs the API 37 image it installs.
- A release workflow for `v*` tags: main's CI must have passed for the tag's commit; the AAR is
  attached to the GitHub release; crates.io Trusted Publishing runs only when asked for.
- The README installs the crate from crates.io and the AAR from the GitHub release, and its Rust code
  compiles as a doctest.
- The planning documents moved from `docs/implementation` to `docs/design`.
- The README's code is `examples/delivery.rs`, which CI compiles for Android. The README now says to add
  `kotlin-stdlib`: a file dependency on the AAR does not bring it, and an app without Kotlin fails with
  `NoClassDefFoundError` at the first call.
- Mutation tests: every mutant of the host code fails a test (`cargo mutants`, `.cargo/mutants.toml`).
  Pull requests test the mutants of the lines they change.
- `update` no longer checks for a record that is `unknown` without awaiting a post: the lifecycle
  never stores one.
- Publishing to crates.io waits for the AAR job and runs in the `crates-io` environment.

## 0.1.0-alpha.1

The first alpha on crates.io: the Rust lifecycle (`start`, `update`, `end`, `list`, `capabilities`)
with idempotent keys, ordered revisions, durable receipts and reconciliation with Android's active
notifications; the JSON bridge and its JNI transport; and the Android AAR with the progress and
countdown templates and promoted ongoing notifications on API 36 and newer.
