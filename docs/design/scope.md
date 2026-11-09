# Scope

## Problem

Android Live Updates need the framework's notification APIs, but a native Rust application should
not have to duplicate lifecycle, validation and ordering rules in Kotlin. The libraries found when the
project started were Kotlin libraries or framework plugins, not a standalone Rust library.

## In this version

- A promoted ongoing notification where Android allows one, with a regular ongoing notification as
  the fallback the caller may choose.
- Standard, BigText, rich progress, timer and metric system templates.
- Typed capabilities, visibility, promotion state and stable error codes.
- Idempotent keys, ordered revisions, durable receipts and reconciliation after a restart.
- A Gradle AAR and instructions for native Rust applications.

## Not in this version

- Arbitrary caller-provided intents, callbacks, `RemoteViews` or vendor-extra maps.
- Android framework components written in Rust.
- Any promise that a device promotes a notification: OEM system UIs decide.
- Shared presentation code with iOS ActivityKit.

## Quality bar

- Rust and Kotlin line coverage of at least 80 %, with the Rust coverage of the JNI code measured on
  an emulator.
- Instrumentation tests on the newest stable Android API (37) and its emulator image.
- Every requirement in [the specification](../spec.md) mapped to tests.
