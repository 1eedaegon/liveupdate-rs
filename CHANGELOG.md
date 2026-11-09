# Changelog

## Unreleased

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

## 0.1.0-alpha.1

The first alpha on crates.io: the Rust lifecycle (`start`, `update`, `end`, `list`, `capabilities`)
with idempotent keys, ordered revisions, durable receipts and reconciliation with Android's active
notifications; the JSON bridge and its JNI transport; and the Android AAR with the progress and
countdown templates and promoted ongoing notifications on API 36 and newer.
