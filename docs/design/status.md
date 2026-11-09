# Status

## Done

- The Rust lifecycle: start, update, end, list and capabilities, with validation, stable errors,
  durable receipts and reconciliation.
- The JSON bridge and JNI transport; the AAR with schema v1 and rich schema v2 system templates.
- Instrumentation tests on an API 37 emulator for every adapter and JNI path, with Kotlin and Rust
  coverage.
- Rich presentation, action/event, fallback and Rust/JNI tests on a Galaxy S23 running Android 17 /
  One UI 9.

## Not done

- Tauri-side TSX/React projection and JavaScript event routing over the new core event drain.
- Instrumentation for process recreation.
- A check on an Android release below 36 (the adapter's API 35 behavior is tested on API 37).
- A native Rust Android sample, and moving `tauri-plugin-live-activity` onto this crate.
