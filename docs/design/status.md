# Status

## Done

- The Rust lifecycle: start, update, end, list and capabilities, with validation, stable errors,
  durable receipts and reconciliation.
- The JSON bridge and the JNI transport; the AAR with the progress and countdown templates.
- Instrumentation tests on an API 37 emulator for every adapter and JNI path, with Kotlin and Rust
  coverage.
- The regular-notification fallback, checked once on a Galaxy S23 on its earlier firmware.

## Not done

- Actions and events pushed from Android to Rust.
- Instrumentation for process recreation.
- A check on an Android release below 36 (the adapter's API 35 behavior is tested on API 37).
- The Galaxy S23 on Android 17 and One UI 9.
- A native Rust Android sample, and moving `tauri-plugin-live-activity` onto this crate.
