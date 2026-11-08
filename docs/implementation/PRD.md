# Product requirements

## Problem

Android Live Updates require Android framework notification APIs, but a native Rust application
should not need to own duplicated lifecycle, validation and ordering logic in Kotlin. Existing
libraries found during repository and registry research are platform-specific Kotlin or framework
plugins rather than a standalone Rust-first library.

## Goal

Provide a stable Rust API for capabilities, start, update, end, list and events. Keep the managed
Android surface narrow and reusable from native Rust, Tauri and other Rust hosts.

## First release

- Promoted ongoing notification request with regular ongoing-notification fallback.
- System templates supported by the Android Live Updates API.
- Typed capabilities, visibility, promotion state and stable errors.
- Idempotent keys, ordered revisions, actions, dismissal and process recovery.
- A Gradle AAR plus native Rust integration instructions.

## Non-goals

- Implementing Android framework components entirely in Rust.
- Custom `RemoteViews` for promoted Live Updates.
- Pretending that promotion is guaranteed across devices or OEM system UIs.
- Sharing presentation code with iOS ActivityKit.

## Acceptance

- Rust and Android tests each meet the 80% line-coverage gate.
- Instrumentation passes on the newest stable Android API using the latest available Pixel phone
  emulator profile; currently Android 17 / API 37.
- Unsupported Android versions prove the declared fallback behavior.
- At least one physical-device run verifies OEM behavior before a stable release.
