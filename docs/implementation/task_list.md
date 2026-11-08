# Task list

## Phase 0 — repository standardization

- [x] Search GitHub code and crates.io for a standalone Rust implementation.
- [x] Create and clone `1eedaegon/liveupdate-rs`.
- [x] Record product, architecture, system and technical decisions.
- [x] Establish the first response-contract tests and Rust CI gates.
- [x] Define the Android 17 / API 37 emulator test policy.

## Phase 1 — Rust core

- [ ] Specify start, update, end, list and event contracts.
- [ ] Add validation and stable error mapping with tests first.
- [ ] Implement lifecycle, revisions, idempotency and a fake backend.
- [ ] Add event fan-out and persistence ports.

## Phase 2 — Android extraction

- [ ] Extract notification construction from `tauri-plugin-live-activity` without Tauri.
- [ ] Replace duplicated Kotlin lifecycle rules with the Rust lifecycle.
- [ ] Implement JNI initialization and managed action/dismiss receivers.
- [ ] Build and test the AAR.

## Phase 3 — device tests and consumers

- [ ] Add instrumentation for post, update, end, action, dismissal and process recreation.
- [ ] Run it on the latest stable Pixel phone emulator and Android API.
- [ ] Verify fallback on older Android and do an OEM physical-device pass.
- [ ] Add a native Rust Android sample.
- [ ] Migrate `tauri-plugin-live-activity` to this crate.
- [ ] Publish an alpha crate and AAR after review.
