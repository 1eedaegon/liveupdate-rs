# Task list

## Phase 0 — repository standardization

- [x] Search GitHub code and crates.io for a standalone Rust implementation.
- [x] Create and clone `1eedaegon/liveupdate-rs`.
- [x] Record product, architecture, system and technical decisions.
- [x] Establish the first response-contract tests and Rust CI gates.
- [x] Define the Android 17 / API 37 emulator test policy.

## Phase 1 — Rust core

- [x] Specify start, update, end and list contracts.
- [x] Add validation and stable error mapping with tests first.
- [x] Implement lifecycle, revisions, idempotency and a fake backend.
- [x] Add the persistence port used by durable lifecycle receipts.
- [ ] Add event fan-out for actions and dismissals.

## Phase 2 — Android extraction

- [x] Extract notification construction from `tauri-plugin-live-activity` without Tauri.
- [x] Replace duplicated Kotlin template-lifecycle rules with the Rust lifecycle.
- [x] Implement JNI initialization and bounded managed commands.
- [x] Build and unit test the AAR.
- [ ] Add managed action/dismiss event receivers.

## Phase 3 — device tests and consumers

- [x] Add native Rust instrumentation for post, update, list and end.
- [ ] Add instrumentation for action, dismissal and process recreation.
- [x] Run it on a Pixel 9 Pro XL API 37 emulator.
- [x] Verify regular-notification fallback on a Galaxy S23 physical device.
- [ ] Verify compatibility on an older Android API.
- [ ] Add a native Rust Android sample.
- [ ] Migrate `tauri-plugin-live-activity` to this crate.
- [ ] Publish an alpha crate and AAR after review.
